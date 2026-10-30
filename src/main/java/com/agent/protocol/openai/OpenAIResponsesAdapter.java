package com.agent.protocol.openai;

import com.agent.contract.AgentException;
import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.ErrorCodes;
import com.agent.contract.Message;
import com.agent.contract.ToolSpec;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import com.agent.model.ProviderType;
import com.agent.pipeline.Interceptor;
import com.agent.protocol.ProtocolAdapter;
import com.agent.serialize.JacksonConfig;
import com.agent.transport.FrameMode;
import com.agent.transport.HttpRequestSpec;
import com.agent.transport.HttpResponse;
import com.agent.transport.SseFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Responses API 适配（POST /responses）。
 * 请求：model、instructions（system 合并）、input（消息列表）、temperature、max_output_tokens、stream。
 * 流式事件：response.output_text.delta 增量、response.completed 汇总（含 usage）。
 */
public final class OpenAIResponsesAdapter implements ProtocolAdapter {

    @Override
    public ProviderType type() {
        return ProviderType.OPENAI_RESPONSES;
    }

    @Override
    public FrameMode frameMode() {
        return FrameMode.SSE;
    }

    @Override
    public HttpRequestSpec buildRequest(Provider provider, ModelConfig model, ChatRequest request, boolean stream) {
        ObjectNode body = JacksonConfig.mapper().createObjectNode();
        body.put("model", model.modelName());

        // system 消息合并为 instructions；其余进 input
        StringBuilder instructions = new StringBuilder();
        ArrayNode input = body.putArray("input");
        for (Message m : request.messages()) {
            if (m.role() == Message.Role.SYSTEM) {
                if (!instructions.isEmpty()) {
                    instructions.append('\n');
                }
                instructions.append(m.content() == null ? "" : m.content());
                continue;
            }
            ObjectNode item = input.addObject();
            item.put("role", m.role() == Message.Role.ASSISTANT ? "assistant"
                    : m.role() == Message.Role.TOOL ? "user" : "user");
            item.put("content", m.content() == null ? "" : m.content());
        }
        if (!instructions.isEmpty()) {
            body.put("instructions", instructions.toString());
        }

        Double temperature = request.temperature() != null ? request.temperature() : model.temperature();
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : model.maxTokens();
        if (maxTokens != null) {
            body.put("max_output_tokens", maxTokens);
        }
        if (stream) {
            body.put("stream", true);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", JacksonConfig.mapper().valueToTree(spec.parameters()));
            }
        }
        if ("json".equals(request.responseFormat())) {
            body.putObject("text").putObject("format").put("type", "json_object");
        }
        model.extra().forEach((k, v) -> body.set(k, JacksonConfig.mapper().valueToTree(v)));
        request.extra().forEach((k, v) -> body.set(k, JacksonConfig.mapper().valueToTree(v)));

        return HttpRequestSpec.builder()
                .uri(OpenAIAdapter.join(provider.baseUrl(), "/responses"))
                .header("Authorization", "Bearer " + provider.apiKey())
                .body(body.toString())
                .build();
    }

    @Override
    public Flux<ChatEvent> parse(Flux<SseFrame> frames) {
        return frames
                .transform(df -> Flux.defer(() -> {
                    OpenAIResponsesEventParser parser = new OpenAIResponsesEventParser();
                    return df.concatMapIterable(parser::parse)
                            .concatWith(Flux.defer(() -> Flux.fromIterable(parser.done())));
                }))
                .doOnDiscard(SseFrame.class, SseFrame::release);
    }

    @Override
    public Flux<ChatEvent> parseResponse(HttpResponse response) {
        if (!response.ok()) {
            return Flux.error(new AgentException(
                    response.status() == 429 ? ErrorCodes.RATE_LIMITED
                            : response.status() >= 500 ? ErrorCodes.MODEL_ERROR : ErrorCodes.BAD_REQUEST,
                    "HTTP " + response.status() + ": " + response.bodyText(),
                    response.status() == 429 || response.status() >= 500));
        }
        try {
            JsonNode root = JacksonConfig.mapper().readTree(response.body());
            List<ChatEvent> events = new ArrayList<>(3);
            StringBuilder text = new StringBuilder();
            for (JsonNode item : root.path("output")) {
                for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asText())) {
                        text.append(part.path("text").asText());
                    }
                }
            }
            if (!text.isEmpty()) {
                events.add(ChatEvent.token(text.toString()));
            }
            JsonNode usage = root.path("usage");
            if (usage.has("total_tokens")) {
                events.add(new ChatEvent.UsageEvent(
                        usage.path("input_tokens").asInt(0),
                        usage.path("output_tokens").asInt(0),
                        usage.path("total_tokens").asInt(0)));
            }
            events.add(new ChatEvent.DoneEvent("stop"));
            return Flux.fromIterable(events);
        } catch (Exception e) {
            return Flux.error(new AgentException(ErrorCodes.MODEL_ERROR,
                    "Responses 响应解析失败: " + e.getMessage(), false, e));
        }
    }
}

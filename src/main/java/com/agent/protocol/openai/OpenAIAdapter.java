package com.agent.protocol.openai;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.contract.ToolSpec;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import com.agent.model.ProviderType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI（与 OpenAI 兼容端点）协议适配。
 */
public final class OpenAIAdapter implements com.agent.protocol.ProtocolAdapter {

    @Override
    public ProviderType type() {
        return ProviderType.OPENAI_CHAT_COMPLETIONS;
    }

    @Override
    public FrameMode frameMode() {
        return FrameMode.SSE;
    }

    @Override
    public HttpRequestSpec buildRequest(Provider provider, ModelConfig model, ChatRequest request, boolean stream) {
        ObjectNode body = JacksonConfig.mapper().createObjectNode();
        body.put("model", model.modelName());
        ArrayNode messages = body.putArray("messages");
        for (Message m : request.messages()) {
            ObjectNode node = messages.addObject();
            switch (m.role()) {
                case SYSTEM -> node.put("role", "system");
                case USER -> node.put("role", "user");
                case ASSISTANT -> node.put("role", "assistant");
                case TOOL -> node.put("role", "tool");
            }
            if (m.content() != null) {
                node.put("content", m.content());
            } else {
                node.put("content", "");
            }
            if (m.toolCallId() != null) {
                node.put("tool_call_id", m.toolCallId());
            }
            if (!m.toolCalls().isEmpty()) {
                ArrayNode tcs = node.putArray("tool_calls");
                for (com.agent.contract.ToolCall tc : m.toolCalls()) {
                    ObjectNode t = tcs.addObject();
                    t.put("id", tc.id());
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tc.name());
                    fn.put("arguments", JacksonConfig.mapper().valueToTree(tc.arguments()).toString());
                }
            }
        }
        Double temperature = request.temperature() != null ? request.temperature() : model.temperature();
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : model.maxTokens();
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (stream) {
            body.put("stream", true);
            body.putObject("stream_options").put("include_usage", true);
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
            body.putObject("response_format").put("type", "json_object");
        }
        Map<String, Object> extra = new LinkedHashMap<>(model.extra());
        extra.putAll(request.extra());
        extra.forEach((k, v) -> body.set(k, JacksonConfig.mapper().valueToTree(v)));

        return HttpRequestSpec.builder()
                .uri(join(provider.baseUrl(), "/chat/completions"))
                .header("Authorization", "Bearer " + provider.apiKey())
                .body(body.toString())
                .build();
    }

    @Override
    public Flux<ChatEvent> parse(Flux<SseFrame> frames) {
        return frames
                .transform(df -> Flux.defer(() -> {
                    OpenAIChatEventParser parser = new OpenAIChatEventParser();
                    return df.concatMapIterable(parser::parse)
                            .concatWith(Flux.defer(() -> Flux.fromIterable(parser.done())));
                }))
                .doOnDiscard(SseFrame.class, SseFrame::release);
    }

    @Override
    public Flux<ChatEvent> parseResponse(HttpResponse response) {
        List<ChatEvent> events = new ArrayList<>(4);
        if (!response.ok()) {
            return Flux.error(new com.agent.contract.AgentException(
                    response.status() == 429 ? com.agent.contract.ErrorCodes.RATE_LIMITED
                            : response.status() >= 500 ? com.agent.contract.ErrorCodes.MODEL_ERROR
                            : com.agent.contract.ErrorCodes.BAD_REQUEST,
                    "HTTP " + response.status() + ": " + response.bodyText(),
                    response.status() == 429 || response.status() >= 500));
        }
        try {
            JsonNode root = JacksonConfig.mapper().readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String content = message.path("content").asText("");
            if (content != null && !content.isEmpty()) {
                events.add(ChatEvent.token(content));
            }
            for (JsonNode tc : message.path("tool_calls")) {
                events.add(new ChatEvent.ToolCallCompleteEvent(
                        tc.path("index").asInt(0),
                        tc.path("id").asText(""),
                        tc.path("function").path("name").asText(""),
                        com.agent.tool.ToolCallParser.parse(tc.path("function").path("arguments").asText("{}"))));
            }
            JsonNode usage = root.path("usage");
            if (usage.has("total_tokens")) {
                events.add(new ChatEvent.UsageEvent(
                        usage.path("prompt_tokens").asInt(0),
                        usage.path("completion_tokens").asInt(0),
                        usage.path("total_tokens").asInt(0)));
            }
            events.add(new ChatEvent.DoneEvent(choice.path("finish_reason").asText("stop")));
            return Flux.fromIterable(events);
        } catch (Exception e) {
            return Flux.error(new com.agent.contract.AgentException(
                    com.agent.contract.ErrorCodes.MODEL_ERROR, "响应解析失败: " + e.getMessage(), false, e));
        }
    }

    public static String join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }
}

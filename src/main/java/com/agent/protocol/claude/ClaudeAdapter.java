package com.agent.protocol.claude;

import com.agent.contract.AgentException;
import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.ErrorCodes;
import com.agent.contract.Message;
import com.agent.contract.ToolSpec;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import com.agent.model.ProviderType;
import com.agent.protocol.ProtocolAdapter;
import com.agent.protocol.openai.OpenAIAdapter;
import com.agent.serialize.JacksonConfig;
import com.agent.transport.FrameMode;
import com.agent.transport.HttpRequestSpec;
import com.agent.transport.HttpResponse;
import com.agent.transport.SseFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Claude messages 协议适配。
 */
public final class ClaudeAdapter implements ProtocolAdapter {

    static final String VERSION = "2023-06-01";

    @Override
    public ProviderType type() {
        return ProviderType.CLAUDE;
    }

    @Override
    public FrameMode frameMode() {
        return FrameMode.SSE;
    }

    @Override
    public HttpRequestSpec buildRequest(Provider provider, ModelConfig model, ChatRequest request, boolean stream) {
        ObjectNode body = JacksonConfig.mapper().createObjectNode();
        body.put("model", model.modelName());
        // Claude 要求 max_tokens 必填
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : model.maxTokens();
        body.put("max_tokens", maxTokens == null ? 4096 : maxTokens);
        Double temperature = request.temperature() != null ? request.temperature() : model.temperature();
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        // system 消息合并到顶层 system 字段
        StringBuilder system = new StringBuilder();
        ArrayNode messages = body.putArray("messages");
        for (Message m : request.messages()) {
            if (m.role() == Message.Role.SYSTEM) {
                if (!system.isEmpty()) {
                    system.append('\n');
                }
                system.append(m.content() == null ? "" : m.content());
                continue;
            }
            ObjectNode node = messages.addObject();
            node.put("role", m.role() == Message.Role.ASSISTANT ? "assistant" : "user");
            if (!m.toolCalls().isEmpty()) {
                ArrayNode content = node.putArray("content");
                if (m.content() != null && !m.content().isEmpty()) {
                    content.addObject().put("type", "text").put("text", m.content());
                }
                for (com.agent.contract.ToolCall tc : m.toolCalls()) {
                    ObjectNode t = content.addObject();
                    t.put("type", "tool_use");
                    t.put("id", tc.id());
                    t.put("name", tc.name());
                    t.set("input", JacksonConfig.mapper().valueToTree(tc.arguments()));
                }
            } else if (m.role() == Message.Role.TOOL) {
                ObjectNode tu = node.putArray("content").addObject();
                tu.put("type", "tool_result");
                tu.put("tool_use_id", m.toolCallId() == null ? "" : m.toolCallId());
                tu.put("content", m.content() == null ? "" : m.content());
            } else {
                node.put("content", m.content() == null ? "" : m.content());
            }
        }
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }
        if (stream) {
            body.put("stream", true);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("name", spec.name());
                t.put("description", spec.description());
                t.set("input_schema", JacksonConfig.mapper().valueToTree(spec.parameters()));
            }
        }
        model.extra().forEach((k, v) -> body.set(k, JacksonConfig.mapper().valueToTree(v)));
        request.extra().forEach((k, v) -> body.set(k, JacksonConfig.mapper().valueToTree(v)));

        return HttpRequestSpec.builder()
                .uri(OpenAIAdapter.join(provider.baseUrl(), "/messages"))
                .header("x-api-key", provider.apiKey() == null ? "" : provider.apiKey())
                .header("anthropic-version", VERSION)
                .body(body.toString())
                .build();
    }

    @Override
    public Flux<ChatEvent> parse(Flux<SseFrame> frames) {
        return frames
                .transform(df -> Flux.defer(() -> {
                    ClaudeEventParser parser = new ClaudeEventParser();
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
        List<ChatEvent> events = new ArrayList<>(4);
        try {
            JsonNode root = JacksonConfig.mapper().readTree(response.body());
            StringBuilder text = new StringBuilder();
            List<ChatEvent.ToolCallCompleteEvent> toolEvents = new ArrayList<>();
            int index = 0;
            for (JsonNode block : root.path("content")) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    text.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    toolEvents.add(new ChatEvent.ToolCallCompleteEvent(index++,
                            block.path("id").asText(""), block.path("name").asText(""),
                            com.agent.tool.ToolCallParser.parse(block.path("input").toString())));
                }
            }
            if (!text.isEmpty()) {
                events.add(ChatEvent.token(text.toString()));
            }
            events.addAll(toolEvents);
            int in = root.path("usage").path("input_tokens").asInt(0);
            int out = root.path("usage").path("output_tokens").asInt(0);
            events.add(new ChatEvent.UsageEvent(in, out, in + out));
            events.add(new ChatEvent.DoneEvent(root.path("stop_reason").asText("stop")));
            return Flux.fromIterable(events);
        } catch (Exception e) {
            return Flux.error(new AgentException(ErrorCodes.MODEL_ERROR,
                    "Claude 响应解析失败: " + e.getMessage(), false, e));
        }
    }
}

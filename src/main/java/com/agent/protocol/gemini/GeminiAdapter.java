package com.agent.protocol.gemini;

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
 * Google Gemini generateContent 协议适配（streamGenerateContent?alt=sse）。
 */
public final class GeminiAdapter implements ProtocolAdapter {

    @Override
    public ProviderType type() {
        return ProviderType.GEMINI;
    }

    @Override
    public FrameMode frameMode() {
        return FrameMode.SSE;
    }

    @Override
    public HttpRequestSpec buildRequest(Provider provider, ModelConfig model, ChatRequest request, boolean stream) {
        ObjectNode body = JacksonConfig.mapper().createObjectNode();
        ArrayNode contents = body.putArray("contents");
        for (Message m : request.messages()) {
            if (m.role() == Message.Role.SYSTEM) {
                body.putObject("systemInstruction").putArray("parts").addObject()
                        .put("text", m.content() == null ? "" : m.content());
                continue;
            }
            ObjectNode c = contents.addObject();
            c.put("role", m.role() == Message.Role.ASSISTANT ? "model" : "user");
            ArrayNode parts = c.putArray("parts");
            if (!m.toolCalls().isEmpty()) {
                for (com.agent.contract.ToolCall tc : m.toolCalls()) {
                    parts.addObject().putObject("functionCall")
                            .put("name", tc.name())
                            .set("args", JacksonConfig.mapper().valueToTree(tc.arguments()));
                }
                continue;
            }
            if (m.role() == Message.Role.TOOL) {
                parts.addObject().putObject("functionResponse")
                        .put("name", m.toolCallId() == null ? "" : m.toolCallId())
                        .putObject("response").put("result", m.content() == null ? "" : m.content());
                continue;
            }
            parts.addObject().put("text", m.content() == null ? "" : m.content());
        }
        ObjectNode gen = body.putObject("generationConfig");
        Double temperature = request.temperature() != null ? request.temperature() : model.temperature();
        if (temperature != null) {
            gen.put("temperature", temperature);
        }
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : model.maxTokens();
        if (maxTokens != null) {
            gen.put("maxOutputTokens", maxTokens);
        }
        if ("json".equals(request.responseFormat())) {
            gen.put("responseMimeType", "application/json");
        }
        if (!request.tools().isEmpty()) {
            ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (ToolSpec spec : request.tools()) {
                ObjectNode d = declarations.addObject();
                d.put("name", spec.name());
                d.put("description", spec.description());
                d.set("parameters", JacksonConfig.mapper().valueToTree(spec.parameters()));
            }
        }
        model.extra().forEach((k, v) -> body.set(k, JacksonConfig.mapper().valueToTree(v)));

        String path = "/models/" + model.modelName() + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
        return HttpRequestSpec.builder()
                .uri(OpenAIAdapter.join(provider.baseUrl(), path))
                .header("x-goog-api-key", provider.apiKey() == null ? "" : provider.apiKey())
                .body(body.toString())
                .build();
    }

    @Override
    public Flux<ChatEvent> parse(Flux<SseFrame> frames) {
        return frames
                .transform(df -> Flux.defer(() -> {
                    GeminiEventParser parser = new GeminiEventParser();
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
            return Flux.fromIterable(new GeminiEventParser().parseData(root));
        } catch (Exception e) {
            return Flux.error(new AgentException(ErrorCodes.MODEL_ERROR,
                    "Gemini 响应解析失败: " + e.getMessage(), false, e));
        }
    }

}

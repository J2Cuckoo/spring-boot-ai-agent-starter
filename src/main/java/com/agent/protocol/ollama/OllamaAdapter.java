package com.agent.protocol.ollama;

import com.agent.contract.AgentException;
import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.ErrorCodes;
import com.agent.contract.Message;
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
 * Ollama 本地模型适配：/api/chat，NDJSON 流式帧，无 api-key。
 */
public final class OllamaAdapter implements ProtocolAdapter {

    @Override
    public ProviderType type() {
        return ProviderType.OLLAMA;
    }

    @Override
    public FrameMode frameMode() {
        return FrameMode.NDJSON;
    }

    @Override
    public HttpRequestSpec buildRequest(Provider provider, ModelConfig model, ChatRequest request, boolean stream) {
        ObjectNode body = JacksonConfig.mapper().createObjectNode();
        body.put("model", model.modelName());
        ArrayNode messages = body.putArray("messages");
        for (Message m : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", switch (m.role()) {
                case SYSTEM -> "system";
                case ASSISTANT -> "assistant";
                case TOOL -> "tool";
                default -> "user";
            });
            node.put("content", m.content() == null ? "" : m.content());
            if (!m.toolCalls().isEmpty()) {
                node.put("tool_calls", JacksonConfig.mapper().valueToTree(m.toolCalls()).toString());
            }
        }
        if (stream) {
            body.put("stream", true);
        } else {
            body.put("stream", false);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (com.agent.contract.ToolSpec spec : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", JacksonConfig.mapper().valueToTree(spec.parameters()));
            }
        }
        ObjectNode options = body.putObject("options");
        Double temperature = request.temperature() != null ? request.temperature() : model.temperature();
        if (temperature != null) {
            options.put("temperature", temperature);
        }
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : model.maxTokens();
        if (maxTokens != null) {
            options.put("num_predict", maxTokens);
        }
        model.extra().forEach((k, v) -> options.set(k, JacksonConfig.mapper().valueToTree(v)));
        request.extra().forEach((k, v) -> body.set(k, JacksonConfig.mapper().valueToTree(v)));

        return HttpRequestSpec.builder()
                .uri(OpenAIAdapter.join(provider.baseUrl(), "/api/chat"))
                .body(body.toString())
                .build();
    }

    @Override
    public Flux<ChatEvent> parse(Flux<SseFrame> frames) {
        return frames
                .transform(df -> Flux.defer(() -> {
                    OllamaChatEventParser parser = new OllamaChatEventParser();
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
            String content = root.path("message").path("content").asText("");
            if (!content.isEmpty()) {
                events.add(ChatEvent.token(content));
            }
            int prompt = root.path("prompt_eval_count").asInt(0);
            int eval = root.path("eval_count").asInt(0);
            events.add(new ChatEvent.UsageEvent(prompt, eval, prompt + eval));
            events.add(new ChatEvent.DoneEvent("stop"));
            return Flux.fromIterable(events);
        } catch (Exception e) {
            return Flux.error(new AgentException(ErrorCodes.MODEL_ERROR,
                    "Ollama 响应解析失败: " + e.getMessage(), false, e));
        }
    }
}

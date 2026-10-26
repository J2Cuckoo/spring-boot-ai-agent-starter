package com.agent.protocol.ollama;

import com.agent.contract.ChatEvent;
import com.agent.contract.ErrorCodes;
import com.agent.serialize.ZeroCopyJsonParser;
import com.agent.transport.SseFrame;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama NDJSON 事件解析：每帧一个 JSON 对象，done=true 为最后一帧。
 */
public final class OllamaChatEventParser {

    private boolean doneEmitted;

    public List<ChatEvent> parse(SseFrame frame) {
        try {
            JsonNode root = ZeroCopyJsonParser.readTree(frame.data());
            List<ChatEvent> events = new ArrayList<>(3);
            if (root.has("error")) {
                events.add(new ChatEvent.ErrorEvent(ErrorCodes.MODEL_ERROR,
                        root.get("error").asText(), false));
                return events;
            }
            String content = root.path("message").path("content").asText("");
            if (!content.isEmpty()) {
                events.add(ChatEvent.token(content));
            }
            if (root.path("done").asBoolean(false)) {
                int prompt = root.path("prompt_eval_count").asInt(0);
                int eval = root.path("eval_count").asInt(0);
                events.add(new ChatEvent.UsageEvent(prompt, eval, prompt + eval));
                events.add(new ChatEvent.DoneEvent(root.path("done_reason").asText("stop")));
                doneEmitted = true;
            }
            return events;
        } catch (IOException e) {
            return List.of(new ChatEvent.ErrorEvent(ErrorCodes.MODEL_ERROR,
                    "Ollama 帧解析失败: " + e.getMessage(), false));
        } finally {
            frame.release();
        }
    }

    public List<ChatEvent> done() {
        if (doneEmitted) {
            return List.of();
        }
        doneEmitted = true;
        return List.of(new ChatEvent.DoneEvent("stop"));
    }
}

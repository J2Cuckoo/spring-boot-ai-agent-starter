package com.agent.protocol.openai;

import com.agent.contract.ChatEvent;
import com.agent.contract.ErrorCodes;
import com.agent.serialize.ZeroCopyJsonParser;
import com.agent.transport.SseFrame;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Responses API SSE 事件解析：response.output_text.delta 增量文本，
 * response.completed 携带 usage 与完整输出，response.failed / error 为错误。
 */
public final class OpenAIResponsesEventParser {

    private boolean doneEmitted;

    public List<ChatEvent> parse(SseFrame frame) {
        try {
            String event = frame.event() == null ? "" : frame.event();
            JsonNode data = ZeroCopyJsonParser.readTree(frame.data());
            List<ChatEvent> events = new ArrayList<>(2);
            switch (event) {
                case "response.output_text.delta" -> {
                    String delta = data.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        events.add(ChatEvent.token(delta));
                    }
                }
                case "response.completed" -> {
                    JsonNode usage = data.path("response").path("usage");
                    if (usage.has("total_tokens")) {
                        events.add(new ChatEvent.UsageEvent(
                                usage.path("input_tokens").asInt(0),
                                usage.path("output_tokens").asInt(0),
                                usage.path("total_tokens").asInt(0)));
                    }
                    events.add(new ChatEvent.DoneEvent("stop"));
                    doneEmitted = true;
                }
                case "response.failed" -> events.add(ChatEvent.error(ErrorCodes.MODEL_ERROR,
                        data.path("response").path("error").path("message").asText("响应失败"), false));
                case "error" -> events.add(ChatEvent.error(ErrorCodes.MODEL_ERROR,
                        data.path("message").asText("响应错误"), false));
                default -> {
                    // 其他事件（in_progress、output_item.added 等）忽略
                }
            }
            return events;
        } catch (IOException e) {
            return List.of(ChatEvent.error(ErrorCodes.MODEL_ERROR,
                    "Responses 事件解析失败: " + e.getMessage(), false));
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

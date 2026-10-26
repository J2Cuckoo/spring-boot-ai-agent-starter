package com.agent.protocol.gemini;

import com.agent.contract.ChatEvent;
import com.agent.contract.ErrorCodes;
import com.agent.serialize.ZeroCopyJsonParser;
import com.agent.transport.SseFrame;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Gemini generateContent 事件解析（SSE data 帧或聚合响应通用，有状态）。
 */
public final class GeminiEventParser {

    private boolean doneEmitted;

    public List<ChatEvent> parseData(JsonNode root) {
        List<ChatEvent> events = new ArrayList<>(3);
        if (root.has("error")) {
            events.add(new ChatEvent.ErrorEvent(ErrorCodes.MODEL_ERROR,
                    root.path("error").path("message").asText("模型侧错误"), false));
            return events;
        }
        JsonNode candidate = root.path("candidates").path(0);
        int toolIndex = 0;
        for (JsonNode part : candidate.path("content").path("parts")) {
            String text = part.path("text").asText(null);
            if (text != null && !text.isEmpty()) {
                events.add(ChatEvent.token(text));
                continue;
            }
            JsonNode fc = part.get("functionCall");
            if (fc != null && !fc.isNull()) {
                // Gemini 无 call id，以工具名充当 id，functionResponse 回填时直接使用
                events.add(new ChatEvent.ToolCallCompleteEvent(toolIndex++,
                        fc.path("name").asText(""), fc.path("name").asText(""),
                        com.agent.tool.ToolCallParser.parse(fc.path("args").toString())));
            }
        }
        JsonNode usage = root.get("usageMetadata");
        if (usage != null && usage.has("totalTokenCount")) {
            events.add(new ChatEvent.UsageEvent(
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0),
                    usage.path("totalTokenCount").asInt(0)));
        }
        String finish = candidate.path("finishReason").asText(null);
        if (finish != null && !doneEmitted) {
            events.add(new ChatEvent.DoneEvent(finish));
            doneEmitted = true;
        }
        return events;
    }

    public List<ChatEvent> parse(SseFrame frame) {
        try {
            JsonNode root = ZeroCopyJsonParser.readTree(frame.data());
            return parseData(root);
        } catch (IOException e) {
            return List.of(new ChatEvent.ErrorEvent(ErrorCodes.MODEL_ERROR,
                    "Gemini 帧解析失败: " + e.getMessage(), false));
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

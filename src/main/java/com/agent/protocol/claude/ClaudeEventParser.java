package com.agent.protocol.claude;

import com.agent.contract.ChatEvent;
import com.agent.contract.ErrorCodes;
import com.agent.serialize.ZeroCopyJsonParser;
import com.agent.transport.SseFrame;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude messages SSE 事件解析（按 event 类型分派，有状态，每次订阅新建实例）。
 */
public final class ClaudeEventParser {

    private final Map<Integer, ToolAccumulator> tools = new LinkedHashMap<>();
    private int inputTokens;
    private int outputTokens;
    private String stopReason;

    private static final class ToolAccumulator {
        final String id;
        final String name;
        final StringBuilder args = new StringBuilder();

        ToolAccumulator(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public List<ChatEvent> parse(SseFrame frame) {
        try {
            String event = frame.event() == null ? "" : frame.event();
            if (event.isEmpty() || "ping".equals(event)) {
                return event.isEmpty() ? List.of() : List.of(ChatEvent.heartbeat());
            }
            JsonNode data = ZeroCopyJsonParser.readTree(frame.data());
            List<ChatEvent> events = new ArrayList<>(2);
            switch (event) {
                case "message_start" -> inputTokens = data.path("message").path("usage").path("input_tokens").asInt(0);
                case "content_block_start" -> {
                    JsonNode block = data.path("content_block");
                    int index = data.path("index").asInt(0);
                    if ("tool_use".equals(block.path("type").asText())) {
                        tools.put(index, new ToolAccumulator(
                                block.path("id").asText(""), block.path("name").asText("")));
                    }
                }
                case "content_block_delta" -> {
                    int index = data.path("index").asInt(0);
                    JsonNode delta = data.path("delta");
                    String type = delta.path("type").asText("");
                    if ("text_delta".equals(type)) {
                        String text = delta.path("text").asText("");
                        if (!text.isEmpty()) {
                            events.add(ChatEvent.token(text));
                        }
                    } else if ("input_json_delta".equals(type)) {
                        ToolAccumulator acc = tools.get(index);
                        if (acc != null) {
                            String partial = delta.path("partial_json").asText("");
                            acc.args.append(partial);
                            events.add(new ChatEvent.ToolCallDeltaEvent(index, acc.id, acc.name, partial));
                        }
                    }
                }
                case "content_block_stop" -> {
                    int index = data.path("index").asInt(0);
                    ToolAccumulator acc = tools.remove(index);
                    if (acc != null) {
                        events.add(new ChatEvent.ToolCallCompleteEvent(index, acc.id, acc.name,
                                com.agent.tool.ToolCallParser.parse(acc.args.toString())));
                    }
                }
                case "message_delta" -> {
                    stopReason = data.path("delta").path("stop_reason").asText(stopReason);
                    outputTokens = data.path("usage").path("output_tokens").asInt(outputTokens);
                }
                case "message_stop" -> {
                    events.add(new ChatEvent.UsageEvent(inputTokens, outputTokens, inputTokens + outputTokens));
                    events.add(new ChatEvent.DoneEvent(stopReason == null ? "stop" : stopReason));
                }
                case "error" -> events.add(new ChatEvent.ErrorEvent(ErrorCodes.MODEL_ERROR,
                        data.path("error").path("message").asText("模型侧错误"), false));
                default -> {
                    // 未知事件忽略
                }
            }
            return events;
        } catch (IOException e) {
            return List.of(new ChatEvent.ErrorEvent(ErrorCodes.MODEL_ERROR,
                    "Claude SSE 帧解析失败: " + e.getMessage(), false));
        } finally {
            frame.release();
        }
    }

    /** 流结束兜底（无 message_stop 时）。 */
    public List<ChatEvent> done() {
        if (stopReason == null) {
            List<ChatEvent> events = new ArrayList<>(tools.size() + 1);
            tools.forEach((index, acc) -> events.add(new ChatEvent.ToolCallCompleteEvent(
                    index, acc.id, acc.name, com.agent.tool.ToolCallParser.parse(acc.args.toString()))));
            tools.clear();
            events.add(new ChatEvent.UsageEvent(inputTokens, outputTokens, inputTokens + outputTokens));
            events.add(new ChatEvent.DoneEvent("stop"));
            return events;
        }
        return List.of();
    }
}

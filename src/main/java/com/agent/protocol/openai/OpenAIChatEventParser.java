package com.agent.protocol.openai;

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
 * OpenAI chat/completions SSE 事件解析（有状态，每次订阅新建实例）。
 * 累积 tool_calls 增量，在 [DONE] 或 finish_reason 时补发 ToolCallCompleteEvent。
 */
public final class OpenAIChatEventParser {

    private final Map<Integer, ToolAccumulator> tools = new LinkedHashMap<>();
    private String finishReason;
    private boolean doneEmitted;

    private static final class ToolAccumulator {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    public List<ChatEvent> parse(SseFrame frame) {
        try {
            String text = frame.dataAsText().trim();
            if ("[DONE]".equals(text)) {
                return done();
            }
            JsonNode root = ZeroCopyJsonParser.readTree(frame.data());
            List<ChatEvent> events = new ArrayList<>(3);
            JsonNode err = root.get("error");
            if (err != null && !err.isNull()) {
                events.add(new ChatEvent.ErrorEvent(ErrorCodes.MODEL_ERROR,
                        err.path("message").asText("模型侧错误"), false));
                return events;
            }
            JsonNode usage = root.get("usage");
            if (usage != null && usage.isObject() && usage.has("total_tokens")) {
                events.add(new ChatEvent.UsageEvent(
                        usage.path("prompt_tokens").asInt(0),
                        usage.path("completion_tokens").asInt(0),
                        usage.path("total_tokens").asInt(0)));
            }
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                JsonNode delta = choice.get("delta");
                if (delta != null) {
                    String content = delta.path("content").asText(null);
                    if (content != null && !content.isEmpty()) {
                        events.add(ChatEvent.token(content));
                    }
                    JsonNode toolCalls = delta.get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            int index = tc.path("index").asInt(0);
                            ToolAccumulator acc = tools.computeIfAbsent(index, i -> new ToolAccumulator());
                            if (tc.hasNonNull("id")) {
                                acc.id = tc.get("id").asText();
                            }
                            JsonNode fn = tc.get("function");
                            if (fn != null) {
                                if (fn.hasNonNull("name")) {
                                    acc.name = fn.get("name").asText();
                                }
                                String argsDelta = fn.path("arguments").asText("");
                                if (!argsDelta.isEmpty()) {
                                    acc.args.append(argsDelta);
                                    events.add(new ChatEvent.ToolCallDeltaEvent(
                                            index, acc.id, acc.name, argsDelta));
                                }
                            }
                        }
                    }
                }
                String fr = choice.path("finish_reason").asText(null);
                if (fr != null) {
                    finishReason = fr;
                }
            }
            return events;
        } catch (IOException e) {
            return List.of(new ChatEvent.ErrorEvent(ErrorCodes.MODEL_ERROR,
                    "SSE 帧解析失败: " + e.getMessage(), false));
        } finally {
            frame.release();
        }
    }

    /** 流正常结束（[DONE]）时的事件收尾。 */
    public List<ChatEvent> done() {
        List<ChatEvent> events = new ArrayList<>(tools.size() + 1);
        if (!doneEmitted) {
            tools.forEach((index, acc) -> events.add(new ChatEvent.ToolCallCompleteEvent(
                    index, acc.id, acc.name, com.agent.tool.ToolCallParser.parse(acc.args.toString()))));
            events.add(new ChatEvent.DoneEvent(finishReason == null ? "stop" : finishReason));
            doneEmitted = true;
        }
        return events;
    }
}

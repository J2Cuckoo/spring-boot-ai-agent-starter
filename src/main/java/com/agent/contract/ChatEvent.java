package com.agent.contract;

import java.util.Map;

/**
 * 统一流式事件模型：sealed interface + 8 个 record。
 */
public sealed interface ChatEvent {

    record TokenEvent(String delta, long timestamp) implements ChatEvent {
    }

    record ToolCallDeltaEvent(int index, String id, String name, String argumentsDelta) implements ChatEvent {
    }

    record ToolCallCompleteEvent(int index, String id, String name, Map<String, Object> arguments) implements ChatEvent {
    }

    record UsageEvent(int promptTokens, int completionTokens, int totalTokens) implements ChatEvent {
    }

    record DoneEvent(String finishReason) implements ChatEvent {
    }

    record ErrorEvent(String code, String message, boolean retryable) implements ChatEvent {
    }

    record PartialEvent(String contentSoFar) implements ChatEvent {
    }

    record HeartbeatEvent(long timestamp) implements ChatEvent {
    }

    static ChatEvent token(String delta) {
        return new TokenEvent(delta, System.currentTimeMillis());
    }

    static ChatEvent heartbeat() {
        return new HeartbeatEvent(System.currentTimeMillis());
    }

    static ChatEvent error(String code, String message, boolean retryable) {
        return new ErrorEvent(code, message, retryable);
    }
}

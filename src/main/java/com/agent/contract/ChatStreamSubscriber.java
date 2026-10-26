package com.agent.contract;

/**
 * 面向非 Reactor 使用者的流式回调订阅。
 */
public interface ChatStreamSubscriber {

    default void onToken(String delta) {
    }

    default void onToolCall(ToolCall call) {
    }

    default void onUsage(Usage usage) {
    }

    default void onHeartbeat() {
    }

    default void onComplete(String finishReason) {
    }

    default void onError(Throwable error) {
    }
}

package com.agent.contract;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * 订阅句柄：包装 Reactor Disposable，可 close 释放。
 */
public final class StreamSubscription implements AutoCloseable {

    private final Disposable disposable;

    private StreamSubscription(Disposable disposable) {
        this.disposable = disposable;
    }

    public static StreamSubscription subscribe(Flux<ChatEvent> flux, ChatStreamSubscriber subscriber) {
        Disposable d = flux.subscribe(
                e -> dispatch(subscriber, e),
                subscriber::onError,
                () -> subscriber.onComplete(null));
        return new StreamSubscription(d);
    }

    /** 把统一事件分发给回调订阅者（供流式 DSL 共用）。 */
    public static void dispatch(ChatStreamSubscriber subscriber, ChatEvent e) {
        if (e instanceof ChatEvent.TokenEvent t) {
            subscriber.onToken(t.delta());
        } else if (e instanceof ChatEvent.ToolCallCompleteEvent c) {
            subscriber.onToolCall(new ToolCall(c.id(), c.name(), c.arguments()));
        } else if (e instanceof ChatEvent.UsageEvent u) {
            subscriber.onUsage(new Usage(u.promptTokens(), u.completionTokens(), u.totalTokens()));
        } else if (e instanceof ChatEvent.HeartbeatEvent) {
            subscriber.onHeartbeat();
        } else if (e instanceof ChatEvent.DoneEvent done) {
            subscriber.onComplete(done.finishReason());
        } else if (e instanceof ChatEvent.ErrorEvent err) {
            subscriber.onError(new AgentException(err.code(), err.message(), err.retryable()));
        }
    }

    public void dispose() {
        disposable.dispose();
    }

    public boolean isDisposed() {
        return disposable.isDisposed();
    }

    @Override
    public void close() {
        dispose();
    }
}

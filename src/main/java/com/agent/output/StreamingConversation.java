package com.agent.output;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatStreamSubscriber;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 流式订阅声明阶段：由 {@code conversation.stream()} 进入。
 * {@code subscribe(...)} 声明事件回调（可多次、可链式），
 * 最终仍以 {@code write()} 收尾——write 永远是链的绝对结尾，且阻塞至整个事件流消费完毕。
 *
 * <pre>{@code
 * agent.chat("写一个快速排序")
 *         .stream()
 *         .subscribe(e -> {
 *             if (e instanceof ChatEvent.TokenEvent t) {
 *                 System.out.print(t.delta());
 *             }
 *         })
 *         .write();
 * }</pre>
 */
public final class StreamingConversation {

    private final Conversation conversation;
    private final List<Consumer<ChatEvent>> consumers = new ArrayList<>();
    private final List<ChatStreamSubscriber> subscribers = new ArrayList<>();
    private boolean terminated;

    StreamingConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    /** 声明逐事件回调（可多次调用，全部生效）。 */
    public StreamingConversation subscribe(Consumer<ChatEvent> onNext) {
        requireActive();
        consumers.add(onNext);
        return this;
    }

    /** 声明结构化回调订阅者（onToken/onUsage/onComplete/onError，可多次调用）。 */
    public StreamingConversation subscribe(ChatStreamSubscriber subscriber) {
        requireActive();
        subscribers.add(subscriber);
        return this;
    }

    /** 流式终结：发起请求，事件逐个分发给已声明的回调，阻塞至流完成。write 永远是结尾。 */
    public void write() {
        if (terminated) {
            throw new IllegalStateException("流式链已以 write() 终结，不可重复终结");
        }
        terminated = true;
        conversation.markTerminated();
        conversation.agent().executeEvents(conversation, true)
                .doOnNext(e -> {
                    for (Consumer<ChatEvent> c : consumers) {
                        c.accept(e);
                    }
                    for (ChatStreamSubscriber s : subscribers) {
                        com.agent.contract.StreamSubscription.dispatch(s, e);
                    }
                })
                .doOnError(err -> subscribers.forEach(s -> s.onError(err)))
                .doOnComplete(() -> subscribers.forEach(s -> s.onComplete(null)))
                .blockLast();
    }

    private void requireActive() {
        if (terminated) {
            throw new IllegalStateException("流式链已以 write() 终结，不可再订阅");
        }
    }
}

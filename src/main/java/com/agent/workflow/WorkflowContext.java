package com.agent.workflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 贯穿工作流全程的上下文：变量、消息、轮次计数、人工信号与取消标记。
 */
public final class WorkflowContext {

    /** 人工干预信号。 */
    public record HumanSignal(boolean approved, Object payload) {
    }

    private final Map<String, Object> vars = new ConcurrentHashMap<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final AtomicReference<WorkflowStateMachine> stateMachine = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<HumanSignal>> pendingSignal = new AtomicReference<>();
    private volatile boolean cancelled;

    public Object get(String key) {
        return vars.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        return (T) vars.get(key);
    }

    public void put(String key, Object value) {
        if (value == null) {
            vars.remove(key);
        } else {
            vars.put(key, value);
        }
    }

    public List<String> messages() {
        return messages;
    }

    public void addMessage(String message) {
        messages.add(message);
    }

    /** 计数 +1 并返回新值（循环轮次、迭代计数）。 */
    public int bump(String key) {
        return counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    public int current(String key) {
        AtomicInteger c = counters.get(key);
        return c == null ? 0 : c.get();
    }

    void attach(WorkflowStateMachine sm) {
        stateMachine.set(sm);
    }

    public WorkflowStateMachine stateMachine() {
        return stateMachine.get();
    }

    /** 等待人工信号（每次调用生成新的等待点）。 */
    public CompletableFuture<HumanSignal> awaitHumanSignal() {
        CompletableFuture<HumanSignal> future = new CompletableFuture<>();
        pendingSignal.set(future);
        return future;
    }

    /** 发送人工信号；无等待点返回 false。 */
    public boolean sendHumanSignal(boolean approved, Object payload) {
        CompletableFuture<HumanSignal> f = pendingSignal.getAndSet(null);
        if (f == null) {
            return false;
        }
        return f.complete(new HumanSignal(approved, payload));
    }

    public void cancel() {
        this.cancelled = true;
        CompletableFuture<HumanSignal> f = pendingSignal.getAndSet(null);
        if (f != null) {
            f.complete(new HumanSignal(false, "cancelled"));
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }
}

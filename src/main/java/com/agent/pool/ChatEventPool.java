package com.agent.pool;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 高频小对象池：复用 TokenEvent 载体，降低 GC 压力。
 * 池化对象被下游 Flux 持有期间不可复用，仅在生命周期受控时启用。
 */
public final class ChatEventPool {

    private final int capacity;
    private final Deque<PooledTokenEvent> pool = new ArrayDeque<>();

    public ChatEventPool(int capacity) {
        this.capacity = Math.max(8, capacity);
    }

    public synchronized PooledTokenEvent borrow(String delta, long timestamp) {
        PooledTokenEvent e = pool.pollLast();
        if (e == null) {
            e = new PooledTokenEvent();
        }
        e.reset(delta, timestamp);
        return e;
    }

    public synchronized void recycle(PooledTokenEvent event) {
        if (pool.size() < capacity) {
            event.reset(null, 0);
            pool.addLast(event);
        }
    }

    public synchronized int size() {
        return pool.size();
    }

    public int capacity() {
        return capacity;
    }
}

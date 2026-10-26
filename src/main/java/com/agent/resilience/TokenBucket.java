package com.agent.resilience;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 令牌桶限流：按模型维度控制 QPS。
 * 无锁实现：把 (剩余令牌, 上次回填时间) 打包为不可变 State，CAS 原子置换，
 * 避免高并发下 synchronized 单锁把请求串行化。
 */
public final class TokenBucket {

    private record State(double tokens, long lastRefillNanos) {
    }

    private final double capacity;
    private final double refillPerSecond;
    private final AtomicReference<State> state;

    public TokenBucket(double permitsPerSecond, double burst) {
        this.capacity = Math.max(1, burst);
        this.refillPerSecond = Math.max(0.0001, permitsPerSecond);
        this.state = new AtomicReference<>(new State(this.capacity, System.nanoTime()));
    }

    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public boolean tryAcquire(int permits) {
        for (; ; ) {
            State s = state.get();
            long now = System.nanoTime();
            double tokens = Math.min(capacity,
                    s.tokens() + (now - s.lastRefillNanos()) / 1e9 * refillPerSecond);
            if (tokens < permits) {
                // 令牌不足也推进水位与时间戳，避免长期空转后回填时间不累计
                if (tokens > s.tokens()) {
                    state.compareAndSet(s, new State(tokens, now));
                }
                return false;
            }
            if (state.compareAndSet(s, new State(tokens - permits, now))) {
                return true;
            }
        }
    }

    public double availableTokens() {
        State s = state.get();
        return Math.min(capacity,
                s.tokens() + (System.nanoTime() - s.lastRefillNanos()) / 1e9 * refillPerSecond);
    }
}

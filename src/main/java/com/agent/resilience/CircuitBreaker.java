package com.agent.resilience;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 熔断器：CLOSED → OPEN（失败率超阈值）→ HALF_OPEN（等待期满）→ 探测成功 CLOSED / 失败 OPEN。
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int windowSize;
    private final double failureRateThreshold;   // 0-100
    private final long waitDurationMs;
    private final Deque<Boolean> window = new ArrayDeque<>();
    private State state = State.CLOSED;
    private long openedAt;
    private int halfOpenProbes;

    public CircuitBreaker(int windowSize, double failureRateThreshold, long waitDurationMs) {
        this.windowSize = Math.max(2, windowSize);
        this.failureRateThreshold = Math.max(1, failureRateThreshold);
        this.waitDurationMs = Math.max(1, waitDurationMs);
    }

    /** 是否放行本次调用。 */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - openedAt >= waitDurationMs) {
                    state = State.HALF_OPEN;
                    halfOpenProbes = 0;
                    return true;
                }
                return false;
            case HALF_OPEN:
            default:
                return halfOpenProbes < 2;
        }
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            window.clear();
        }
        window.addLast(true);
        trim();
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            trip();
            return;
        }
        window.addLast(false);
        trim();
        if (window.size() >= windowSize && failureRate() >= failureRateThreshold) {
            trip();
        }
    }

    private void trip() {
        state = State.OPEN;
        openedAt = System.currentTimeMillis();
        window.clear();
    }

    private void trim() {
        while (window.size() > windowSize) {
            window.removeFirst();
        }
    }

    public synchronized double failureRate() {
        if (window.isEmpty()) {
            return 0;
        }
        long failures = window.stream().filter(ok -> !ok).count();
        return failures * 100.0 / window.size();
    }

    public synchronized State state() {
        return state;
    }
}

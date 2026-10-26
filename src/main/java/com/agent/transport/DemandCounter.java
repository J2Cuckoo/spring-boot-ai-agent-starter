package com.agent.transport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 背压需求计数（package-private）：request(n) 累加，每发出一帧消耗一个。
 */
final class DemandCounter {

    private final AtomicLong demand = new AtomicLong();

    void add(long n) {
        if (n > 0) {
            demand.addAndGet(n);
        }
    }

    boolean hasDemand() {
        return demand.get() > 0;
    }

    boolean consume() {
        for (; ; ) {
            long v = demand.get();
            if (v <= 0) {
                return false;
            }
            if (demand.compareAndSet(v, v - 1)) {
                return true;
            }
        }
    }
}

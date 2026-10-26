package com.agent.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程工厂构建工具：统一命名 + daemon，用于 EventLoop 之外的业务线程池。
 */
public final class ThreadFactoryBuilder {

    public static ThreadFactory build(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private ThreadFactoryBuilder() {
    }
}

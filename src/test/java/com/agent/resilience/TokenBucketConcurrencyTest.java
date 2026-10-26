package com.agent.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketConcurrencyTest {

    @Test
    void noOversubscriptionUnderContention() throws Exception {
        // 容量 100，refill 关闭（0.0001/s 近似无回填），并发 32 线程各抢 10 次
        TokenBucket bucket = new TokenBucket(0.0001, 100);
        int threads = 32;
        int attemptsPerThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int a = 0; a < attemptsPerThread; a++) {
                        if (bucket.tryAcquire()) {
                            granted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        // 无锁 CAS 下成功数绝不能超过容量（100）
        assertThat(granted.get()).isLessThanOrEqualTo(100);
        // 且令牌确实发满（至少发出大部分，证明 CAS 未活锁）
        assertThat(granted.get()).isGreaterThanOrEqualTo(90);
    }
}

package com.agent.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void burstThenRefill() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(2, 2);   // 2 token/s，容量 2
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();      // 桶空
        Thread.sleep(600);                              // 约 1.2 token 回填
        assertThat(bucket.tryAcquire()).isTrue();
    }

    @Test
    void multiPermitAcquire() {
        TokenBucket bucket = new TokenBucket(1, 4);
        assertThat(bucket.tryAcquire(3)).isTrue();
        assertThat(bucket.tryAcquire(2)).isFalse();
        assertThat(bucket.tryAcquire(1)).isTrue();
    }
}

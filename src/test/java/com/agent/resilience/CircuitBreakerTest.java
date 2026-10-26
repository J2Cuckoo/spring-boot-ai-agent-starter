package com.agent.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    @Test
    void opensAfterFailureRateThreshold() {
        CircuitBreaker cb = new CircuitBreaker(4, 50, 10_000);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);   // 2/3 < 50%？窗口未满不开

        cb.recordFailure();   // 2/4 = 50% 达阈值
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.tryAcquire()).isFalse();
    }

    @Test
    void halfOpenAfterWaitThenCloseOnSuccess() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, 50, 200);
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        Thread.sleep(250);
        assertThat(cb.tryAcquire()).isTrue();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenReopensOnFailure() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, 50, 200);
        cb.recordFailure();
        cb.recordFailure();
        Thread.sleep(250);
        assertThat(cb.tryAcquire()).isTrue();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}

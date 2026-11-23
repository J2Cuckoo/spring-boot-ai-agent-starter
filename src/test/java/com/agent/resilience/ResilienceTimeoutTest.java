package com.agent.resilience;

import com.agent.contract.AgentException;
import com.agent.contract.ErrorCodes;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三段式超时：firstTokenTimeout / interEventIdle / requestTimeout 的虚拟时间验证。
 * 注意：流必须在 withVirtualTime 供应商内创建——Flux.interval 等操作符在装配期绑定调度器，
 * 供应商外创建会绑到真实调度器，虚拟时间推进时事件不流动。
 */
class ResilienceTimeoutTest {

    private static String code(Throwable e) {
        return e instanceof AgentException ae ? ae.code() : e.getClass().getSimpleName();
    }

    @Test
    void firstTokenTimeoutFiresOnPrefillSilence() {
        HeartbeatKeepAlive hb = new HeartbeatKeepAlive(Duration.ofMinutes(10), Duration.ofMinutes(2));
        StepVerifier.withVirtualTime(() -> hb.apply(Flux.<String>never()))
                .expectSubscription()
                .thenAwait(Duration.ofMinutes(11))
                .expectErrorMatches(e -> ErrorCodes.FIRST_TOKEN_TIMEOUT.equals(code(e)))
                .verify();
    }

    @Test
    void firstEventThenSilenceFiresIdleTimeout() {
        HeartbeatKeepAlive hb = new HeartbeatKeepAlive(Duration.ofMinutes(10), Duration.ofMinutes(2));
        StepVerifier.withVirtualTime(() -> hb.apply(Flux.just("a")
                        .delaySubscription(Duration.ofMinutes(1))
                        .concatWith(Flux.<String>never())))
                .expectSubscription()
                .thenAwait(Duration.ofMinutes(1))
                .expectNext("a")
                .thenAwait(Duration.ofMinutes(3))
                .expectErrorMatches(e -> ErrorCodes.IDLE_TIMEOUT.equals(code(e)))
                .verify();
    }

    @Test
    void steadyEventsCompleteWithoutIdleTimeout() {
        HeartbeatKeepAlive hb = new HeartbeatKeepAlive(Duration.ofMinutes(10), Duration.ofMinutes(2));
        StepVerifier.withVirtualTime(() -> hb.apply(
                        Flux.range(0, 5).delayElements(Duration.ofSeconds(30))))
                .expectSubscription()
                .thenAwait(Duration.ofMinutes(5))
                .expectNextCount(5)
                .verifyComplete();
    }

    @Test
    void disabledDurationsArePassthrough() {
        HeartbeatKeepAlive hb = new HeartbeatKeepAlive(Duration.ZERO, Duration.ZERO);
        Flux<Integer> source = Flux.just(1, 2);
        assertThat(hb.apply(source)).isSameAs(source);

        UserTimeoutGuard guard = new UserTimeoutGuard(Duration.ZERO);
        assertThat(guard.guard(source)).isSameAs(source);
    }

    @Test
    void totalTimeoutFiresOnSilence() {
        UserTimeoutGuard guard = new UserTimeoutGuard(Duration.ofSeconds(30));
        StepVerifier.withVirtualTime(() -> guard.guard(Flux.<String>never()))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(31))
                .expectErrorMatches(e -> ErrorCodes.TIMEOUT.equals(code(e)))
                .verify();
    }

    @Test
    void totalTimeoutFiresWhileEventsKeepFlowing() {
        // 事件每秒一发永不停歇，总超时 9.5s 必须照样切断：这是总超时区别于事件间超时的关键语义
        UserTimeoutGuard guard = new UserTimeoutGuard(Duration.ofMillis(9500));
        StepVerifier.withVirtualTime(() -> guard.guard(Flux.interval(Duration.ofSeconds(1))))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(15))
                .expectNextCount(9)   // t=1s..9s 的 9 个事件，9.5s 处被切断
                .expectErrorMatches(e -> ErrorCodes.TIMEOUT.equals(code(e)))
                .verify();
    }

    @Test
    void managerDefaultsApplyFullGuardChain() {
        ResilienceManager manager = new ResilienceManager(ResilienceConfig.defaults());
        // 默认总超时关闭：guard 应不注入总超时切断
        StepVerifier.withVirtualTime(() -> manager.guard("k", Flux.just("x")))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(1))
                .expectNext("x")
                .verifyComplete();
    }
}

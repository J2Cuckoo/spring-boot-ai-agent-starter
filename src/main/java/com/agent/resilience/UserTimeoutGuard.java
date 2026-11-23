package com.agent.resilience;

import com.agent.contract.AgentException;
import com.agent.contract.ErrorCodes;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 用户级总超时守卫：整次请求限时（从订阅起算的墙钟时长，与事件间节奏无关），
 * 超时抛 TIMEOUT（可重试）。0 或负值 = 关闭。
 */
public final class UserTimeoutGuard {

    private final Duration timeout;

    public UserTimeoutGuard(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration timeout() {
        return timeout;
    }

    public <T> Mono<T> guard(Mono<T> mono) {
        if (off()) {
            return mono;
        }
        return mono.timeout(timeout)
                .onErrorMap(TimeoutException.class, e -> timeoutError());
    }

    public <T> Flux<T> guard(Flux<T> flux) {
        if (off()) {
            return flux;
        }
        // Flux.timeout(Duration) 是「首事件+事件间」语义，表达不了总时长；
        // 用伴随 publisher 到期即报错，保证事件持续流动时也会在总时长处被切断
        return flux.takeUntilOther(Mono.delay(timeout)
                .then(Mono.error(this::timeoutError)));
    }

    private boolean off() {
        return timeout == null || timeout.isZero() || timeout.isNegative();
    }

    private AgentException timeoutError() {
        return new AgentException(ErrorCodes.TIMEOUT, "请求总超时（" + timeout.toMillis() + "ms）", true);
    }
}

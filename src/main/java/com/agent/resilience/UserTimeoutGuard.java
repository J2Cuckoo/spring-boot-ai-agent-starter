package com.agent.resilience;

import com.agent.contract.AgentException;
import com.agent.contract.ErrorCodes;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 用户级超时守卫：整体请求限时，超时抛 TIMEOUT（可重试）。
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
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return mono;
        }
        return mono.timeout(timeout)
                .onErrorMap(TimeoutException.class, e -> timeoutError());
    }

    public <T> Flux<T> guard(Flux<T> flux) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return flux;
        }
        return flux.timeout(timeout)
                .onErrorMap(TimeoutException.class, e -> timeoutError());
    }

    private AgentException timeoutError() {
        return new AgentException(ErrorCodes.TIMEOUT, "请求超时（" + timeout.toMillis() + "ms）", true);
    }
}

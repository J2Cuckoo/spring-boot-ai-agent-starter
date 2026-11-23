package com.agent.resilience;

import com.agent.contract.AgentException;
import com.agent.contract.ErrorCodes;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件流保活（两段式）：
 * <ul>
 *   <li>firstTokenTimeout：首个事件到来前的等待上限，覆盖大上下文 prefill 静默期</li>
 *   <li>interEventIdle：首个事件之后相邻事件间的空闲上限</li>
 * </ul>
 * 超时分别抛 FIRST_TOKEN_TIMEOUT / IDLE_TIMEOUT。
 * Claude 的 ping 会转为 HeartbeatEvent，天然重置事件间计时。
 */
public final class HeartbeatKeepAlive {

    private final Duration firstTokenTimeout;
    private final Duration interEventIdle;

    public HeartbeatKeepAlive(Duration firstTokenTimeout, Duration interEventIdle) {
        this.firstTokenTimeout = firstTokenTimeout;
        this.interEventIdle = interEventIdle;
    }

    public Duration firstTokenTimeout() {
        return firstTokenTimeout;
    }

    public Duration interEventIdle() {
        return interEventIdle;
    }

    public <T> Flux<T> apply(Flux<T> events) {
        boolean noFirst = off(firstTokenTimeout);
        boolean noIdle = off(interEventIdle);
        if (noFirst && noIdle) {
            return events;
        }
        Mono<?> firstDeadline = noFirst ? Mono.never() : Mono.delay(firstTokenTimeout);
        Duration idle = noIdle ? null : interEventIdle;
        return Flux.defer(() -> {
            // 每次订阅独立判定是否已收首事件：首事件前超时是 FIRST_TOKEN_TIMEOUT，其后是 IDLE_TIMEOUT
            AtomicBoolean firstSeen = new AtomicBoolean(false);
            return events
                    .doOnNext(t -> firstSeen.set(true))
                    .timeout(firstDeadline, t -> idle == null ? Mono.never() : Mono.delay(idle))
                    .onErrorMap(TimeoutException.class, e -> firstSeen.get()
                            ? idleError()
                            : firstTokenError());
        });
    }

    private static boolean off(Duration d) {
        return d == null || d.isZero() || d.isNegative();
    }

    private AgentException firstTokenError() {
        return new AgentException(ErrorCodes.FIRST_TOKEN_TIMEOUT,
                "首字节超时（" + firstTokenTimeout.toMillis() + "ms）：模型未在 prefill 静默期内返回数据", true);
    }

    private AgentException idleError() {
        return new AgentException(ErrorCodes.IDLE_TIMEOUT,
                "事件流空闲超时（" + interEventIdle.toMillis() + "ms）", true);
    }
}

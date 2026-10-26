package com.agent.resilience;

import com.agent.contract.AgentException;
import com.agent.contract.ChatEvent;
import com.agent.contract.ErrorCodes;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * 心跳保活：相邻事件间隔超过阈值判 IDLE_TIMEOUT。
 * Claude 的 ping 会转为 HeartbeatEvent，天然重置计时。
 */
public final class HeartbeatKeepAlive {

    private final Duration maxIdle;

    public HeartbeatKeepAlive(Duration maxIdle) {
        this.maxIdle = maxIdle;
    }

    public Duration maxIdle() {
        return maxIdle;
    }

    public <T> Flux<T> apply(Flux<T> events) {
        if (maxIdle == null || maxIdle.isZero() || maxIdle.isNegative()) {
            return events;
        }
        return events.timeout(maxIdle, x -> new AgentException(
                ErrorCodes.IDLE_TIMEOUT, "事件流空闲超时（" + maxIdle.toMillis() + "ms）", true));
    }
}

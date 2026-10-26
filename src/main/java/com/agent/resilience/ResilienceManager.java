package com.agent.resilience;

import com.agent.contract.AgentException;
import com.agent.contract.ErrorCodes;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 容错管理器：按模型维度组合熔断 + 重试 + 总超时 + 心跳。
 * 策略对象（RetryPolicy/UserTimeoutGuard/HeartbeatKeepAlive）在构造期构建一次，
 * guard() 调用零额外分配；pipeline 的 RateLimit/Retry/Timeout 拦截器即本内核在拦截链上的装配形态。
 */
public final class ResilienceManager {

    private final ResilienceConfig config;
    private final ConcurrentMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final RetryPolicy retryPolicy;
    private final UserTimeoutGuard timeoutGuard;
    private final HeartbeatKeepAlive heartbeat;

    public ResilienceManager() {
        this(ResilienceConfig.defaults());
    }

    public ResilienceManager(ResilienceConfig config) {
        this.config = config;
        this.retryPolicy = new RetryPolicy(config.retryMaxAttempts(), config.retryBackoff());
        this.timeoutGuard = new UserTimeoutGuard(config.requestTimeout());
        this.heartbeat = new HeartbeatKeepAlive(config.heartbeatMaxIdle());
    }

    public ResilienceConfig config() {
        return config;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public UserTimeoutGuard timeoutGuard() {
        return timeoutGuard;
    }

    public HeartbeatKeepAlive heartbeat() {
        return heartbeat;
    }

    /** 限流令牌（供 RateLimitInterceptor 使用），未配置限流时恒放行。 */
    public boolean tryAcquirePermit(String key) {
        if (config.rateLimitPermitsPerSecond() <= 0) {
            return true;
        }
        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(config.rateLimitPermitsPerSecond(), config.rateLimitBurst()));
        return bucket.tryAcquire();
    }

    /** 包裹冷流：重试在最外层（重订阅会重跑整个调用链），熔断/超时/心跳逐层生效。 */
    public <T> Flux<T> guard(String key, Flux<T> source) {
        if (!config.enabled()) {
            return source;
        }
        return Flux.defer(() -> {
            if (config.breakerEnabled()) {
                CircuitBreaker cb = breakers.computeIfAbsent(key, k -> new CircuitBreaker(
                        config.breakerWindowSize(),
                        config.breakerFailureRateThreshold(),
                        config.breakerWaitDuration().toMillis()));
                if (!cb.tryAcquire()) {
                    return Flux.error(new AgentException(ErrorCodes.CIRCUIT_OPEN,
                            "熔断开启: " + key, true));
                }
                return timeoutGuard.guard(heartbeat.apply(source))
                        .doOnComplete(cb::recordSuccess)
                        .doOnError(e -> cb.recordFailure());
            }
            return timeoutGuard.guard(heartbeat.apply(source));
        }).retryWhen(retryPolicy.toReactor());
    }
}

package com.agent.resilience;

import com.agent.contract.AgentException;
import com.agent.contract.ErrorCodes;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;

/**
 * 重试策略：指数退避 + 抖动，仅对可重试错误生效。
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration minBackoff;

    public RetryPolicy(int maxAttempts, Duration minBackoff) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.minBackoff = minBackoff == null ? Duration.ofMillis(100) : minBackoff;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration minBackoff() {
        return minBackoff;
    }

    /** 可重试判定：AgentException 按标记；IO 类默认可重试。 */
    public static boolean isRetryable(Throwable t) {
        if (t instanceof AgentException ae) {
            return ae.retryable();
        }
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof IOException || cur instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 转为 Reactor Retry。 */
    public Retry toReactor() {
        return Retry.backoff(Math.max(0, maxAttempts - 1), minBackoff)
                .maxBackoff(minBackoff.multipliedBy(8))
                .jitter(0.2)
                .filter(RetryPolicy::isRetryable)
                .onRetryExhaustedThrow((spec, signal) ->
                        signal.failure() instanceof AgentException ae ? ae
                                : new AgentException(ErrorCodes.MODEL_ERROR,
                                "重试耗尽: " + signal.failure().getMessage(), false, signal.failure()));
    }
}

package com.agent.resilience;

import java.time.Duration;

/**
 * 容错配置。超时为三段式：
 * <ul>
 *   <li>firstTokenTimeout：首字节超时，覆盖大上下文 prefill 静默期（0 = 关闭）</li>
 *   <li>interEventIdle：首事件之后相邻事件间的空闲超时（0 = 关闭）</li>
 *   <li>requestTimeout：整次请求总超时（0 = 关闭，默认关闭；超大 token 场景按需开启）</li>
 * </ul>
 */
public final class ResilienceConfig {

    private final boolean enabled;
    private final Duration requestTimeout;
    private final Duration firstTokenTimeout;
    private final Duration interEventIdle;
    private final int retryMaxAttempts;
    private final Duration retryBackoff;
    private final boolean breakerEnabled;
    private final int breakerWindowSize;
    private final double breakerFailureRateThreshold;
    private final Duration breakerWaitDuration;
    private final double rateLimitPermitsPerSecond;   // 0 = 关闭
    private final double rateLimitBurst;

    private ResilienceConfig(Builder b) {
        this.enabled = b.enabled;
        this.requestTimeout = b.requestTimeout;
        this.firstTokenTimeout = b.firstTokenTimeout;
        this.interEventIdle = b.interEventIdle;
        this.retryMaxAttempts = b.retryMaxAttempts;
        this.retryBackoff = b.retryBackoff;
        this.breakerEnabled = b.breakerEnabled;
        this.breakerWindowSize = b.breakerWindowSize;
        this.breakerFailureRateThreshold = b.breakerFailureRateThreshold;
        this.breakerWaitDuration = b.breakerWaitDuration;
        this.rateLimitPermitsPerSecond = b.rateLimitPermitsPerSecond;
        this.rateLimitBurst = b.rateLimitBurst;
    }

    public static ResilienceConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder()
                .enabled(true)
                .requestTimeout(Duration.ZERO)
                .firstTokenTimeout(Duration.ofMinutes(10))
                .interEventIdle(Duration.ofMinutes(2))
                .retryMaxAttempts(3)
                .retryBackoff(Duration.ofMillis(100))
                .breakerEnabled(true)
                .breakerWindowSize(20)
                .breakerFailureRateThreshold(50)
                .breakerWaitDuration(Duration.ofSeconds(10))
                .rateLimitPermitsPerSecond(0)
                .rateLimitBurst(10);
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration firstTokenTimeout() {
        return firstTokenTimeout;
    }

    public Duration interEventIdle() {
        return interEventIdle;
    }

    public int retryMaxAttempts() {
        return retryMaxAttempts;
    }

    public Duration retryBackoff() {
        return retryBackoff;
    }

    public boolean breakerEnabled() {
        return breakerEnabled;
    }

    public int breakerWindowSize() {
        return breakerWindowSize;
    }

    public double breakerFailureRateThreshold() {
        return breakerFailureRateThreshold;
    }

    public Duration breakerWaitDuration() {
        return breakerWaitDuration;
    }

    public double rateLimitPermitsPerSecond() {
        return rateLimitPermitsPerSecond;
    }

    public double rateLimitBurst() {
        return rateLimitBurst;
    }

    public static final class Builder {

        private boolean enabled;
        private Duration requestTimeout;
        private Duration firstTokenTimeout;
        private Duration interEventIdle;
        private int retryMaxAttempts;
        private Duration retryBackoff;
        private boolean breakerEnabled;
        private int breakerWindowSize;
        private double breakerFailureRateThreshold;
        private Duration breakerWaitDuration;
        private double rateLimitPermitsPerSecond;
        private double rateLimitBurst;

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder requestTimeout(Duration v) {
            this.requestTimeout = v;
            return this;
        }

        public Builder firstTokenTimeout(Duration v) {
            this.firstTokenTimeout = v;
            return this;
        }

        public Builder interEventIdle(Duration v) {
            this.interEventIdle = v;
            return this;
        }

        /**
         * @deprecated 由 interEventIdle 取代；保留旧入口转发，源码兼容。
         */
        @Deprecated
        public Builder heartbeatMaxIdle(Duration v) {
            return interEventIdle(v);
        }

        public Builder retryMaxAttempts(int v) {
            this.retryMaxAttempts = v;
            return this;
        }

        public Builder retryBackoff(Duration v) {
            this.retryBackoff = v;
            return this;
        }

        public Builder breakerEnabled(boolean v) {
            this.breakerEnabled = v;
            return this;
        }

        public Builder breakerWindowSize(int v) {
            this.breakerWindowSize = v;
            return this;
        }

        public Builder breakerFailureRateThreshold(double v) {
            this.breakerFailureRateThreshold = v;
            return this;
        }

        public Builder breakerWaitDuration(Duration v) {
            this.breakerWaitDuration = v;
            return this;
        }

        public Builder rateLimitPermitsPerSecond(double v) {
            this.rateLimitPermitsPerSecond = v;
            return this;
        }

        public Builder rateLimitBurst(double v) {
            this.rateLimitBurst = v;
            return this;
        }

        public ResilienceConfig build() {
            return new ResilienceConfig(this);
        }
    }
}

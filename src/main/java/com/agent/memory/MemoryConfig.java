package com.agent.memory;

import java.time.Duration;

/**
 * 记忆策略配置：database 隔离、反向优化、整合浓缩阈值、热缓存与三层 TTL。
 */
public final class MemoryConfig {

    private final int database;
    private final boolean reverseOptimize;
    private final int consolidationMinRounds;
    private final Duration cacheTtl;
    private final long cacheMaxSizeBytes;
    private final Duration temporaryTtl;
    private final Duration shortTermTtl;

    private MemoryConfig(Builder b) {
        this.database = b.database;
        this.reverseOptimize = b.reverseOptimize;
        this.consolidationMinRounds = b.consolidationMinRounds;
        this.cacheTtl = b.cacheTtl;
        this.cacheMaxSizeBytes = b.cacheMaxSizeBytes;
        this.temporaryTtl = b.temporaryTtl;
        this.shortTermTtl = b.shortTermTtl;
    }

    public static MemoryConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder()
                .database(15)
                .reverseOptimize(false)
                .consolidationMinRounds(100)
                .cacheTtl(Duration.ofMinutes(30))
                .cacheMaxSizeBytes(20L * 1024 * 1024)
                .temporaryTtl(Duration.ofDays(3))
                .shortTermTtl(Duration.ofDays(7));
    }

    public int database() {
        return database;
    }

    public boolean reverseOptimize() {
        return reverseOptimize;
    }

    public int consolidationMinRounds() {
        return consolidationMinRounds;
    }

    public Duration cacheTtl() {
        return cacheTtl;
    }

    public long cacheMaxSizeBytes() {
        return cacheMaxSizeBytes;
    }

    public Duration temporaryTtl() {
        return temporaryTtl;
    }

    public Duration shortTermTtl() {
        return shortTermTtl;
    }

    public static final class Builder {

        private int database;
        private boolean reverseOptimize;
        private int consolidationMinRounds;
        private Duration cacheTtl;
        private long cacheMaxSizeBytes;
        private Duration temporaryTtl;
        private Duration shortTermTtl;

        public Builder database(int v) {
            this.database = v;
            return this;
        }

        public Builder reverseOptimize(boolean v) {
            this.reverseOptimize = v;
            return this;
        }

        public Builder consolidationMinRounds(int v) {
            this.consolidationMinRounds = v;
            return this;
        }

        public Builder cacheTtl(Duration v) {
            this.cacheTtl = v;
            return this;
        }

        public Builder cacheMaxSizeBytes(long v) {
            this.cacheMaxSizeBytes = v;
            return this;
        }

        public Builder temporaryTtl(Duration v) {
            this.temporaryTtl = v;
            return this;
        }

        public Builder shortTermTtl(Duration v) {
            this.shortTermTtl = v;
            return this;
        }

        public MemoryConfig build() {
            return new MemoryConfig(this);
        }
    }
}

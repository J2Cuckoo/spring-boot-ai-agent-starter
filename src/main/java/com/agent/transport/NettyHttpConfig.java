package com.agent.transport;

/**
 * Netty 传输配置：线程数、连接池、各类超时。默认值见 performance-tuning.md。
 */
public final class NettyHttpConfig {

    private final int workerThreads;
    private final int maxConnectionsPerEndpoint;
    private final int maxPendingAcquires;
    private final int acquireTimeoutMs;
    private final int connectTimeoutMs;
    private final int requestTimeoutMs;
    private final int idleTimeoutSec;

    private NettyHttpConfig(Builder b) {
        this.workerThreads = b.workerThreads;
        this.maxConnectionsPerEndpoint = b.maxConnectionsPerEndpoint;
        this.maxPendingAcquires = b.maxPendingAcquires;
        this.acquireTimeoutMs = b.acquireTimeoutMs;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.requestTimeoutMs = b.requestTimeoutMs;
        this.idleTimeoutSec = b.idleTimeoutSec;
    }

    public static NettyHttpConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new Builder()
                .workerThreads(Math.max(2, cores * 2))
                .maxConnectionsPerEndpoint(cores * 4)
                .maxPendingAcquires(65536)
                .acquireTimeoutMs(10_000)
                .connectTimeoutMs(10_000)
                .requestTimeoutMs(120_000)
                .idleTimeoutSec(60);
    }

    public int workerThreads() {
        return workerThreads;
    }

    public int maxConnectionsPerEndpoint() {
        return maxConnectionsPerEndpoint;
    }

    public int maxPendingAcquires() {
        return maxPendingAcquires;
    }

    public int acquireTimeoutMs() {
        return acquireTimeoutMs;
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int requestTimeoutMs() {
        return requestTimeoutMs;
    }

    public int idleTimeoutSec() {
        return idleTimeoutSec;
    }

    public static final class Builder {

        private int workerThreads;
        private int maxConnectionsPerEndpoint;
        private int maxPendingAcquires;
        private int acquireTimeoutMs;
        private int connectTimeoutMs;
        private int requestTimeoutMs;
        private int idleTimeoutSec;

        public Builder workerThreads(int v) {
            this.workerThreads = v;
            return this;
        }

        public Builder maxConnectionsPerEndpoint(int v) {
            this.maxConnectionsPerEndpoint = v;
            return this;
        }

        public Builder maxPendingAcquires(int v) {
            this.maxPendingAcquires = v;
            return this;
        }

        public Builder acquireTimeoutMs(int v) {
            this.acquireTimeoutMs = v;
            return this;
        }

        public Builder connectTimeoutMs(int v) {
            this.connectTimeoutMs = v;
            return this;
        }

        public Builder requestTimeoutMs(int v) {
            this.requestTimeoutMs = v;
            return this;
        }

        public Builder idleTimeoutSec(int v) {
            this.idleTimeoutSec = v;
            return this;
        }

        public NettyHttpConfig build() {
            return new NettyHttpConfig(this);
        }
    }
}

package com.agent.pool;

/**
 * 可池化的 TokenEvent 载体：仅在生命周期受控的内部聚合路径使用，
 * 不直接进入对外的 Flux&lt;ChatEvent&gt;。
 */
public final class PooledTokenEvent {

    private String delta;
    private long timestamp;

    void reset(String delta, long timestamp) {
        this.delta = delta;
        this.timestamp = timestamp;
    }

    public String delta() {
        return delta;
    }

    public long timestamp() {
        return timestamp;
    }

    public com.agent.contract.ChatEvent.TokenEvent toImmutable() {
        return new com.agent.contract.ChatEvent.TokenEvent(delta, timestamp);
    }
}

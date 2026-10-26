package com.agent.transport;

import io.netty.buffer.ByteBuf;

/**
 * 单个流式事件帧：SSE 携带 event 名（可能为 null），NDJSON 的 event 恒为 null。
 * 持有 data 的 ByteBuf 切片，消费方用完须 {@link #release()}。
 */
public record SseFrame(String event, ByteBuf data) {

    public String dataAsText() {
        return data.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    public void release() {
        if (data != null && data.refCnt() > 0) {
            data.release();
        }
    }
}

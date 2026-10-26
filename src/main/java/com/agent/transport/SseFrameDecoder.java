package com.agent.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * 帧解码器：消费 HttpContent，在累积缓冲上原地扫描帧边界，
 * 以 {@code readRetainedSlice} 零拷贝切帧下发。SSE 按 {@code \n\n} 事件帧切，
 * NDJSON 按单行切。收到 LastHttpContent 时先补发尾帧，再以 STREAM_END 事件通知下游。
 */
public final class SseFrameDecoder extends ChannelInboundHandlerAdapter {

    /** 流结束信号（用户事件）。 */
    public enum StreamEnd { INSTANCE }

    private final FrameMode mode;
    private ByteBuf cumulation;

    public SseFrameDecoder(FrameMode mode) {
        this.mode = mode;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpContent content)) {
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            ByteBuf in = content.content();
            if (in.isReadable()) {
                cumulate(ctx, in);
            }
            fireCompleteFrames(ctx);
            if (content instanceof LastHttpContent) {
                emitTrailing(ctx);
                ctx.fireUserEventTriggered(StreamEnd.INSTANCE);
            }
        } finally {
            content.release();
        }
    }

    private void cumulate(ChannelHandlerContext ctx, ByteBuf in) {
        if (cumulation == null) {
            cumulation = in.retain();
            return;
        }
        if (cumulation instanceof CompositeByteBuf cb && cb.numComponents() < 64) {
            cb.addComponent(true, in.retain());
        } else {
            CompositeByteBuf merged = ctx.alloc().compositeBuffer(8);
            merged.addComponent(true, cumulation);
            merged.addComponent(true, in.retain());
            cumulation = merged;
        }
    }

    private void fireCompleteFrames(ChannelHandlerContext ctx) {
        boolean emitted = false;
        while (cumulation != null && cumulation.isReadable()) {
            int[] end = mode == FrameMode.NDJSON
                    ? ByteBufUtils.findNewlineFrame(cumulation)
                    : ByteBufUtils.findSseFrameEnd(cumulation);
            if (end == null) {
                break;
            }
            int len = end[0] - cumulation.readerIndex();
            if (len > 0) {
                ctx.fireChannelRead(cumulation.readRetainedSlice(len));
                emitted = true;
            }
            cumulation.skipBytes(end[1]);
        }
        if (cumulation == null) {
            return;
        }
        if (!cumulation.isReadable()) {
            cumulation.release();
            cumulation = null;
        } else if (emitted) {
            // 已发出的切片与累积缓冲共享内存，压缩会破坏切片内容：
            // 把残余复制到新缓冲后再释放旧缓冲
            ByteBuf tail = cumulation.copy();
            cumulation.release();
            cumulation = tail;
        } else {
            cumulation.discardSomeReadBytes();
        }
    }

    /** 发出不足一个完整帧的尾部内容（NDJSON 最后一行可能无换行）。 */
    private void emitTrailing(ChannelHandlerContext ctx) {
        if (cumulation == null) {
            return;
        }
        try {
            if (cumulation.isReadable()) {
                ctx.fireChannelRead(cumulation.readRetainedSlice(cumulation.readableBytes()));
            }
        } finally {
            cumulation.release();
            cumulation = null;
        }
    }

    /** 丢弃未完成帧（中止/失败路径），不下发。 */
    public void release() {
        ByteBufUtils.releaseQuietly(cumulation);
        cumulation = null;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        release();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        release();
        super.handlerRemoved(ctx);
    }
}

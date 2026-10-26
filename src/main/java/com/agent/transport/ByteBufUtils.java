package com.agent.transport;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * ByteBuf 工具：帧边界扫描、UTF-8 读取、安全释放。
 */
public final class ByteBufUtils {

    /**
     * 查找 SSE 事件帧边界（空白行），返回 {@code [内容结束位置, 分隔符长度]}，
     * 不完整返回 null。支持 {@code \n\n}、{@code \n\r\n}、{@code \r\n\r\n}。
     */
    public static int[] findSseFrameEnd(ByteBuf buf) {
        int reader = buf.readerIndex();
        int limit = buf.writerIndex();
        for (int i = reader; i < limit - 1; i++) {
            byte b = buf.getByte(i);
            if (b == '\n') {
                byte n = buf.getByte(i + 1);
                if (n == '\n') {
                    return new int[]{i, 2};
                }
                if (n == '\r' && i + 2 < limit && buf.getByte(i + 2) == '\n') {
                    return new int[]{i, 3};
                }
            } else if (b == '\r') {
                byte n = buf.getByte(i + 1);
                if (n == '\n') {
                    if (i + 2 < limit && buf.getByte(i + 2) == '\n') {
                        return new int[]{i, 3};
                    }
                    if (i + 3 < limit && buf.getByte(i + 2) == '\r' && buf.getByte(i + 3) == '\n') {
                        return new int[]{i, 4};
                    }
                }
            }
        }
        return null;
    }

    /**
     * 查找 NDJSON 行边界，返回 {@code [内容结束位置, 分隔符长度]}（\n 或 \r\n），不完整返回 null。
     */
    public static int[] findNewlineFrame(ByteBuf buf) {
        int reader = buf.readerIndex();
        int limit = buf.writerIndex();
        for (int i = reader; i < limit; i++) {
            if (buf.getByte(i) == '\n') {
                int end = i;
                int delim = 1;
                if (end > reader && buf.getByte(end - 1) == '\r') {
                    end--;
                    delim = 2;
                }
                return new int[]{end, delim};
            }
        }
        return null;
    }

    public static String toUtf8(ByteBuf buf) {
        return buf.toString(StandardCharsets.UTF_8);
    }

    public static void releaseQuietly(ByteBuf buf) {
        if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }

    private ByteBufUtils() {
    }
}

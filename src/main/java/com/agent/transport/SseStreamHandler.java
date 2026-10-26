package com.agent.transport;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SSE 事件解析：把一个事件帧原始 ByteBuf 解析为 {@link SseFrame}。
 * 解析 {@code event:}/{@code data:} 字段，多个 data 行按规范以换行合并；
 * 单 data 行场景直接 retainedSlice 视图，零拷贝。无 data 字段的帧返回 null（已释放）。
 */
public final class SseStreamHandler {

    public static SseFrame extract(ByteBuf raw, FrameMode mode) {
        if (mode == FrameMode.NDJSON) {
            return new SseFrame(null, raw);
        }
        String event = null;
        List<int[]> dataRanges = new ArrayList<>(2);
        int reader = raw.readerIndex();
        int writer = raw.writerIndex();
        int lineStart = reader;
        for (int i = reader; i <= writer; i++) {
            if (i != writer && raw.getByte(i) != '\n') {
                continue;
            }
            int end = i;
            if (end > lineStart && raw.getByte(end - 1) == '\r') {
                end--;
            }
            if (end > lineStart) {
                byte b0 = raw.getByte(lineStart);
                if (b0 == ':') {
                    // 注释行，忽略
                } else if (matchPrefix(raw, lineStart, end, "data:")) {
                    int s = lineStart + 5;
                    if (s < end && raw.getByte(s) == ' ') {
                        s++;
                    }
                    dataRanges.add(new int[]{s, end});
                } else if (matchPrefix(raw, lineStart, end, "event:")) {
                    int s = lineStart + 6;
                    if (s < end && raw.getByte(s) == ' ') {
                        s++;
                    }
                    byte[] tmp = new byte[end - s];
                    raw.getBytes(s, tmp);
                    event = new String(tmp, StandardCharsets.UTF_8);
                }
            }
            lineStart = i + 1;
        }
        if (dataRanges.isEmpty()) {
            raw.release();
            return null;
        }
        if (dataRanges.size() == 1) {
            int[] r = dataRanges.get(0);
            return new SseFrame(event, raw.retainedSlice(r[0], r[1] - r[0]));
        }
        int total = 0;
        for (int[] r : dataRanges) {
            total += r[1] - r[0] + 1;
        }
        total--;
        ByteBuf out = raw.alloc().buffer(total);
        for (int i = 0; i < dataRanges.size(); i++) {
            int[] r = dataRanges.get(i);
            if (i > 0) {
                out.writeByte('\n');
            }
            out.writeBytes(raw, r[0], r[1] - r[0]);
        }
        raw.release();
        return new SseFrame(event, out);
    }

    private static boolean matchPrefix(ByteBuf buf, int start, int end, String prefix) {
        if (end - start < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (buf.getByte(start + i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private SseStreamHandler() {
    }
}

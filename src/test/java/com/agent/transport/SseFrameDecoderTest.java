package com.agent.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseFrameDecoderTest {

    private static DefaultHttpContent content(String s) {
        return new DefaultHttpContent(Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sseFramesSplitAcrossChunks() {
        EmbeddedChannel ch = new EmbeddedChannel(new SseFrameDecoder(FrameMode.SSE));
        // 半帧 + 跨块的帧边界
        ch.writeInbound(content("data: ab"));
        assertThat((ByteBuf) ch.readInbound()).isNull();
        ch.writeInbound(content("c\n\ndata: de"));
        ByteBuf first = ch.readInbound();
        assertThat(first.toString(StandardCharsets.UTF_8)).isEqualTo("data: abc");
        first.release();
        assertThat((ByteBuf) ch.readInbound()).isNull();
        ch.writeInbound(content("f\r\n\r\n"));

        List<String> frames = new ArrayList<>();
        ByteBuf f;
        while ((f = ch.readInbound()) != null) {
            frames.add(f.toString(StandardCharsets.UTF_8));
            f.release();
        }
        assertThat(frames).containsExactly("data: def");
        ch.finishAndReleaseAll();
    }

    @Test
    void sseMultiLineEventKeptWhole() {
        EmbeddedChannel ch = new EmbeddedChannel(new SseFrameDecoder(FrameMode.SSE));
        ch.writeInbound(content("event: foo\ndata: l1\ndata: l2\n\n"));
        ByteBuf frame = ch.readInbound();
        assertThat(frame.toString(StandardCharsets.UTF_8)).isEqualTo("event: foo\ndata: l1\ndata: l2");
        frame.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void ndjsonSingleLineFrames() {
        EmbeddedChannel ch = new EmbeddedChannel(new SseFrameDecoder(FrameMode.NDJSON));
        ch.writeInbound(content("{\"a\":1}\n{\"a\":2}\r\n{\"a\":3}"));
        // 最后一段无换行，等 LastHttpContent 触发尾帧
        List<String> frames = new ArrayList<>();
        List<Object> events = new ArrayList<>();
        ch.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(io.netty.channel.ChannelHandlerContext ctx, Object evt) {
                events.add(evt);
            }
        });
        ch.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
        ByteBuf f;
        while ((f = ch.readInbound()) != null) {
            frames.add(f.toString(StandardCharsets.UTF_8));
            f.release();
        }
        assertThat(frames).containsExactly("{\"a\":1}", "{\"a\":2}", "{\"a\":3}");
        assertThat(events).contains(SseFrameDecoder.StreamEnd.INSTANCE);
        ch.finishAndReleaseAll();
    }

    @Test
    void extractEventAndDataFields() {
        ByteBuf raw = Unpooled.wrappedBuffer("event: ping\ndata: hello world\n: comment\n".getBytes(StandardCharsets.UTF_8));
        SseFrame frame = SseStreamHandler.extract(raw, FrameMode.SSE);
        assertThat(frame.event()).isEqualTo("ping");
        assertThat(frame.dataAsText()).isEqualTo("hello world");
        frame.release();

        // 多 data 行合并
        ByteBuf multi = Unpooled.wrappedBuffer("data: a\ndata: b\n".getBytes(StandardCharsets.UTF_8));
        SseFrame m = SseStreamHandler.extract(multi, FrameMode.SSE);
        assertThat(m.dataAsText()).isEqualTo("a\nb");
        m.release();

        // 无 data 字段返回 null 并释放
        ByteBuf none = Unpooled.wrappedBuffer(": keep-alive\n".getBytes(StandardCharsets.UTF_8));
        assertThat(SseStreamHandler.extract(none, FrameMode.SSE)).isNull();
        assertThat(none.refCnt()).isZero();
    }
}

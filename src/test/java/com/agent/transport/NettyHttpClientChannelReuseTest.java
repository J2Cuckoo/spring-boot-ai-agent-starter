package com.agent.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：流式请求结束后连接归还池中复用。曾因 StreamBridge.finish() 只移除自身、
 * 不移除 SseFrameDecoder，残留解码器吞掉复用请求的 HttpContent——
 * 后续非流式请求收不到响应体（Mono 永久挂起）、切片泄漏，流式请求被双解析。
 * 单连接池强制复用同一 channel，依次执行 流式 → 非流式 → 流式 验证管线复位。
 */
@Timeout(60)
class NettyHttpClientChannelReuseTest {

    private static final String JSON_BODY = "{\"ok\":true}";

    private NioEventLoopGroup serverGroup;
    private int port;
    private NettyHttpClient client;

    @BeforeEach
    void setUp() throws InterruptedException {
        serverGroup = new NioEventLoopGroup(1);
        ServerBootstrap sb = new ServerBootstrap()
                .group(serverGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpRequestDecoder());
                        ch.pipeline().addLast(new HttpResponseEncoder());
                        ch.pipeline().addLast(new HttpObjectAggregator(1 << 20));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                boolean streaming = req.headers()
                                        .containsValue(HttpHeaderNames.ACCEPT, "text/event-stream", true);
                                if (streaming) {
                                    DefaultHttpResponse resp = new DefaultHttpResponse(
                                            HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                                    HttpUtil.setKeepAlive(resp, true);
                                    HttpUtil.setTransferEncodingChunked(resp, true);
                                    resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
                                    ctx.write(resp);
                                    // 帧边界跨 chunk，同时覆盖解码器累积逻辑
                                    ctx.write(new DefaultHttpContent(
                                            Unpooled.wrappedBuffer("data: o".getBytes(StandardCharsets.UTF_8))));
                                    ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(
                                            "ne\n\ndata: two\n\n".getBytes(StandardCharsets.UTF_8))));
                                    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                                } else {
                                    DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                            Unpooled.wrappedBuffer(JSON_BODY.getBytes(StandardCharsets.UTF_8)));
                                    HttpUtil.setKeepAlive(resp, true);
                                    resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                                    HttpUtil.setContentLength(resp, resp.content().readableBytes());
                                    ctx.writeAndFlush(resp);
                                }
                            }
                        });
                    }
                });
        port = ((java.net.InetSocketAddress) sb.bind("127.0.0.1", 0).sync().channel().localAddress()).getPort();

        client = new NettyHttpClient(NettyHttpConfig.builder()
                .workerThreads(2)
                .maxConnectionsPerEndpoint(1)
                .maxPendingAcquires(8)
                .acquireTimeoutMs(5_000)
                .connectTimeoutMs(5_000)
                .requestTimeoutMs(5_000)
                .idleTimeoutSec(60)
                .build());
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (serverGroup != null) {
            serverGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private HttpRequestSpec spec() {
        return HttpRequestSpec.builder()
                .uri("http://127.0.0.1:" + port + "/v1/test")
                .body("{}")
                .build();
    }

    private List<String> streamOnce() {
        List<String> texts = new CopyOnWriteArrayList<>();
        client.executeStreaming(spec(), FrameMode.SSE)
                .doOnNext(f -> {
                    texts.add(f.dataAsText());
                    f.release();
                })
                .doOnDiscard(SseFrame.class, SseFrame::release)
                .blockLast(java.time.Duration.ofSeconds(8));
        return texts;
    }

    @Test
    void pooledChannelIsCleanAfterStreamingFinish() throws InterruptedException {
        // 1. 流式请求完成，连接归还池
        assertThat(streamOnce()).containsExactly("one", "two");

        // 归还通过 eventLoop 异步执行，等待其落地以强制下一次复用同一 channel
        Thread.sleep(300);

        // 2. 复用连接的非流式请求：残留解码器会吞掉 HttpContent，导致永久挂起（超时失败）
        HttpResponse resp = client.execute(spec())
                .block(java.time.Duration.ofSeconds(8));
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.bodyText()).isEqualTo(JSON_BODY);

        // 3. 再次流式：管线干净，帧不重不漏
        assertThat(streamOnce()).containsExactly("one", "two");
    }
}

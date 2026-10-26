package com.agent.transport;

import com.agent.contract.AgentException;
import com.agent.contract.ErrorCodes;
import com.agent.util.ExceptionUtils;
import com.agent.util.ThreadFactoryBuilder;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 自研 Netty HTTP 客户端：按端点分组 FixedChannelPool，非流式聚合响应，
 * 流式按 SSE/NDJSON 帧下发并做背压（autoRead=false，request(n) 驱动 read()）。
 */
public final class NettyHttpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpClient.class);

    /** 单条流错误体累积上限（字符），防止异常服务端拖垮内存。 */
    private static final int MAX_ERR_BODY_CHARS = 4096;

    private final NettyHttpConfig config;
    private final EventLoopGroup group;
    private final ConcurrentMap<String, FixedChannelPool> pools = new ConcurrentHashMap<>();
    private final SslContext sslContext;
    /** 非阻塞 DNS 解析（带解析缓存）：避免 JDK 阻塞式解析卡死 EventLoop。 */
    private final DnsAddressResolverGroup dnsResolver;

    public NettyHttpClient() {
        this(NettyHttpConfig.defaults());
    }

    public NettyHttpClient(NettyHttpConfig config) {
        this.config = config;
        this.group = new NioEventLoopGroup(config.workerThreads(), ThreadFactoryBuilder.build("agent-netty"));
        this.dnsResolver = new DnsAddressResolverGroup(
                NioDatagramChannel.class, DnsServerAddressStreamProviders.platformDefault());
        try {
            this.sslContext = SslContextBuilder.forClient().build();
        } catch (Exception e) {
            dnsResolver.close();
            group.shutdownGracefully();
            throw new IllegalStateException("初始化 SSL 上下文失败", e);
        }
    }

    public NettyHttpConfig config() {
        return config;
    }

    /** 非流式：聚合完整响应体。 */
    public Mono<HttpResponse> execute(HttpRequestSpec spec) {
        return Mono.create(sink -> {
            URI uri = URI.create(spec.uri());
            group.next().execute(() -> acquireBlocking(spec, uri, sink));
        });
    }

    /** 流式：按帧下发，背压由下游 request(n) 驱动。 */
    public Flux<SseFrame> executeStreaming(HttpRequestSpec spec, FrameMode mode) {
        return Flux.create(sink -> {
            URI uri = URI.create(spec.uri());
            group.next().execute(() -> acquireStreaming(spec, uri, mode, sink));
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    // ------------------------------------------------------------------ 内部

    private void acquireBlocking(HttpRequestSpec spec, URI uri, MonoSink<HttpResponse> sink) {
        FixedChannelPool pool;
        try {
            pool = poolFor(uri);
        } catch (Throwable t) {
            sink.error(mapTransport(t));
            return;
        }
        pool.acquire().addListener((io.netty.util.concurrent.Future<Channel> f) -> {
            if (!f.isSuccess()) {
                sink.error(new AgentException(ErrorCodes.TRANSPORT_ERROR,
                        "获取连接失败: " + ExceptionUtils.describe(f.cause()), true, f.cause()));
                return;
            }
            Channel ch = f.getNow();
            try {
                BlockingBridge bridge = new BlockingBridge(sink, pool);
                ch.pipeline().addLast(bridge);
                ch.writeAndFlush(buildRequest(spec, uri, false)).addListener(wf -> {
                    if (!wf.isSuccess()) {
                        bridge.fail(wf.cause());
                    }
                });
            } catch (Throwable t) {
                closeAndRelease(pool, ch);
                sink.error(mapTransport(t));
            }
        });
    }

    private void acquireStreaming(HttpRequestSpec spec, URI uri, FrameMode mode, FluxSink<SseFrame> sink) {
        FixedChannelPool pool;
        try {
            pool = poolFor(uri);
        } catch (Throwable t) {
            sink.error(mapTransport(t));
            return;
        }
        pool.acquire().addListener((io.netty.util.concurrent.Future<Channel> f) -> {
            if (!f.isSuccess()) {
                sink.error(new AgentException(ErrorCodes.TRANSPORT_ERROR,
                        "获取连接失败: " + ExceptionUtils.describe(f.cause()), true, f.cause()));
                return;
            }
            Channel ch = f.getNow();
            try {
                SseFrameDecoder decoder = new SseFrameDecoder(mode);
                StreamBridge bridge = new StreamBridge(sink, pool, decoder, mode);
                ch.pipeline().addLast(decoder);
                ch.pipeline().addLast(bridge);
                ch.config().setAutoRead(false);
                sink.onRequest(bridge::onRequest);
                sink.onCancel(bridge::abort);
                ch.writeAndFlush(buildRequest(spec, uri, true)).addListener(wf -> {
                    if (!wf.isSuccess()) {
                        bridge.fail(wf.cause());
                    } else {
                        bridge.written();
                    }
                });
            } catch (Throwable t) {
                closeAndRelease(pool, ch);
                sink.error(mapTransport(t));
            }
        });
    }

    private FixedChannelPool poolFor(URI uri) {
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        String key = (tls ? "https" : "http") + "://" + uri.getHost() + ":" + port;
        return pools.computeIfAbsent(key, k -> {
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .resolver(dnsResolver)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMs())
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .remoteAddress(uri.getHost(), port);
            NettyChannelPoolHandler handler =
                    new NettyChannelPoolHandler(tls, uri.getHost(), port, config, sslContext);
            return new FixedChannelPool(bootstrap, handler, ChannelHealthChecker.ACTIVE,
                    FixedChannelPool.AcquireTimeoutAction.FAIL, config.acquireTimeoutMs(),
                    config.maxConnectionsPerEndpoint(), config.maxPendingAcquires(), true, true);
        });
    }

    /** 构建 Netty 请求；URI 由调用方解析一次传入，避免每请求重复解析。 */
    private FullHttpRequest buildRequest(HttpRequestSpec spec, URI uri, boolean streaming) {
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        int port = uri.getPort();
        StringBuilder path = new StringBuilder(
                uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            path.append('?').append(uri.getRawQuery());
        }
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.valueOf(spec.method()),
                path.toString(), Unpooled.wrappedBuffer(spec.body()));
        HttpHeaders h = req.headers();
        boolean defaultPort = port == -1 || (tls && port == 443) || (!tls && port == 80);
        h.set(HttpHeaderNames.HOST, defaultPort ? host : host + ":" + port);
        h.set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        h.set(HttpHeaderNames.CONTENT_LENGTH, spec.body().length);
        h.set(HttpHeaderNames.ACCEPT, streaming ? "text/event-stream" : "application/json");
        h.set(HttpHeaderNames.USER_AGENT, "spring-boot-ai-agent-starter");
        spec.headers().forEach(h::set);
        HttpUtil.setKeepAlive(req, true);
        return req;
    }

    private void closeAndRelease(ChannelPool pool, Channel ch) {
        ch.close().addListener(f -> pool.release(ch));
    }

    /** 网络异常归一化为 AgentException（可重试）。 */
    public static AgentException mapTransport(Throwable t) {
        if (t instanceof AgentException ae) {
            return ae;
        }
        Throwable root = ExceptionUtils.root(t);
        boolean timeout = root instanceof java.util.concurrent.TimeoutException
                || root instanceof io.netty.channel.ConnectTimeoutException
                || root instanceof java.net.SocketTimeoutException;
        if (timeout) {
            return new AgentException(ErrorCodes.TIMEOUT, "请求超时: " + ExceptionUtils.describe(t), true, t);
        }
        if (root instanceof ConnectException ce) {
            return new AgentException(ErrorCodes.TRANSPORT_ERROR, "连接失败: " + ce.getMessage(), true, t);
        }
        if (root instanceof IOException io) {
            return new AgentException(ErrorCodes.TRANSPORT_ERROR, "网络 IO 异常: " + io.getMessage(), true, t);
        }
        return new AgentException(ErrorCodes.TRANSPORT_ERROR, ExceptionUtils.describe(t), true, t);
    }

    // ------------------------------------------------------------------ 非流式桥

    private final class BlockingBridge extends ChannelInboundHandlerAdapter {

        private final MonoSink<HttpResponse> sink;
        private final ChannelPool pool;
        private io.netty.handler.codec.http.HttpResponse headers;
        private CompositeByteBuf body;
        private boolean done;
        private ChannelHandlerContext ctx;

        BlockingBridge(MonoSink<HttpResponse> sink, ChannelPool pool) {
            this.sink = sink;
            this.pool = pool;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof io.netty.handler.codec.http.HttpResponse r) {
                    headers = r;
                    body = ctx.alloc().compositeBuffer();
                } else if (msg instanceof HttpContent c) {
                    if (c.content().isReadable()) {
                        body.addComponent(true, c.content().retain());
                    }
                    if (c instanceof LastHttpContent) {
                        complete(ctx);
                    }
                } else {
                    ctx.fireChannelRead(msg);
                }
            } catch (Throwable t) {
                fail(ctx, t);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        private void complete(ChannelHandlerContext ctx) {
            if (done) {
                return;
            }
            done = true;
            byte[] bytes = new byte[body.readableBytes()];
            body.readBytes(bytes);
            body.release();
            boolean keepAlive = headers != null && HttpUtil.isKeepAlive(headers);
            cleanup(ctx, keepAlive);
            sink.success(new HttpResponse(headers.status().code(), toMap(headers.headers()), bytes));
        }

        private void cleanup(ChannelHandlerContext ctx, boolean keepAlive) {
            Channel ch = ctx.channel();
            ctx.pipeline().remove(this);
            if (keepAlive && ch.isActive()) {
                ch.eventLoop().execute(() -> pool.release(ch));
            } else {
                closeAndRelease(pool, ch);
            }
        }

        void fail(ChannelHandlerContext ctx, Throwable cause) {
            if (done) {
                return;
            }
            done = true;
            if (body != null && body.refCnt() > 0) {
                body.release();
            }
            cleanup(ctx, false);
            sink.error(mapTransport(cause));
        }

        /** 写请求失败等无上下文场景。 */
        void fail(Throwable cause) {
            if (done) {
                return;
            }
            if (ctx != null) {
                fail(ctx, cause);
            } else {
                done = true;
                sink.error(mapTransport(cause));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            fail(ctx, cause);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!done) {
                fail(ctx, new IOException("连接在响应完成前关闭"));
            }
        }
    }

    // ------------------------------------------------------------------ 流式桥

    private final class StreamBridge extends ChannelInboundHandlerAdapter {

        private final FluxSink<SseFrame> sink;
        private final ChannelPool pool;
        private final SseFrameDecoder decoder;
        private final FrameMode mode;
        private final DemandCounter demand = new DemandCounter();

        private io.netty.handler.codec.http.HttpResponse headers;
        private boolean errorMode;
        private boolean finished;
        private boolean written;
        private final StringBuilder errBody = new StringBuilder();

        StreamBridge(FluxSink<SseFrame> sink, ChannelPool pool, SseFrameDecoder decoder, FrameMode mode) {
            this.sink = sink;
            this.pool = pool;
            this.decoder = decoder;
            this.mode = mode;
        }

        void onRequest(long n) {
            demand.add(n);
            maybeRead();
        }

        void written() {
            written = true;
            maybeRead();
        }

        private void maybeRead() {
            Channel ch = currentChannel();
            if (written && !finished && ch != null && ch.isRegistered() && ch.isActive() && demand.hasDemand()) {
                ch.read();
            }
        }

        private Channel currentChannel() {
            return ctx != null ? ctx.channel() : null;
        }

        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        void abort() {
            if (finished || ctx == null) {
                return;
            }
            finished = true;
            decoder.release();
            Channel ch = ctx.channel();
            ctx.pipeline().remove(this);
            closeAndRelease(pool, ch);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof io.netty.handler.codec.http.HttpResponse r) {
                headers = r;
                if (r.status().code() >= 400) {
                    errorMode = true;
                }
                ReferenceCountUtil.release(msg);
                return;
            }
            if (msg instanceof ByteBuf raw) {
                if (errorMode) {
                    // 错误响应体可能是任意格式，不做 SSE 字段解析；累积上限 4KB 防御超大错误体
                    if (errBody.length() < MAX_ERR_BODY_CHARS) {
                        errBody.append(raw.toString(StandardCharsets.UTF_8));
                    }
                    raw.release();
                    return;
                }
                // 解码器发出的原始帧 → 解析 event/data 字段
                SseFrame frame = SseStreamHandler.extract(raw, mode);
                if (frame == null) {
                    return;
                }
                if (finished) {
                    frame.release();
                    return;
                }
                demand.consume();
                sink.next(frame);
                maybeRead();
                return;
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt == SseFrameDecoder.StreamEnd.INSTANCE) {
                if (errorMode) {
                    fail(ctx, httpError());
                } else {
                    finish(ctx);
                }
                return;
            }
            if (evt instanceof IdleStateEvent) {
                fail(ctx, new AgentException(ErrorCodes.IDLE_TIMEOUT,
                        "流式读取空闲超时（" + config.idleTimeoutSec() + "s）", true));
                return;
            }
            ctx.fireUserEventTriggered(evt);
        }

        private AgentException httpError() {
            int code = headers == null ? 500 : headers.status().code();
            String body = errBody.length() > 512 ? errBody.substring(0, 512) : errBody.toString();
            boolean retryable = code == 429 || code >= 500;
            String errCode = code == 429 ? ErrorCodes.RATE_LIMITED
                    : code >= 500 ? ErrorCodes.MODEL_ERROR : ErrorCodes.BAD_REQUEST;
            return new AgentException(errCode, "HTTP " + code + ": " + body, retryable);
        }

        private void finish(ChannelHandlerContext ctx) {
            if (finished) {
                return;
            }
            finished = true;
            boolean keepAlive = headers == null || HttpUtil.isKeepAlive(headers);
            Channel ch = ctx.channel();
            ctx.pipeline().remove(this);
            ch.config().setAutoRead(true);
            if (keepAlive && ch.isActive()) {
                ch.eventLoop().execute(() -> pool.release(ch));
            } else {
                closeAndRelease(pool, ch);
            }
            sink.complete();
        }

        void fail(ChannelHandlerContext ctx, Throwable cause) {
            if (finished) {
                return;
            }
            finished = true;
            decoder.release();
            Channel ch = ctx.channel();
            ctx.pipeline().remove(this);
            closeAndRelease(pool, ch);
            AgentException e = errorMode ? httpError() : mapTransport(cause);
            sink.error(e);
        }

        /** 写请求失败等无上下文场景。 */
        void fail(Throwable cause) {
            if (finished) {
                return;
            }
            if (ctx != null) {
                fail(ctx, cause);
            } else {
                finished = true;
                sink.error(mapTransport(cause));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            fail(ctx, cause);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!finished) {
                fail(ctx, new IOException("连接在流完成前关闭"));
            }
        }
    }

    private static Map<String, String> toMap(HttpHeaders headers) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    @Override
    public void close() {
        pools.values().forEach(ChannelPool::close);
        pools.clear();
        dnsResolver.close();
        group.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS);
        log.debug("NettyHttpClient 已关闭");
    }
}

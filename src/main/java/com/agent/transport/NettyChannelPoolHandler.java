package com.agent.transport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.util.concurrent.TimeUnit;

import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;

/**
 * 连接池 channel 生命周期：新连接（channelCreated）装配 TLS（含主机名校验）、
 * HTTP 编解码、解压与空闲检测。
 * 注意：FixedChannelPool 不使用 Bootstrap 的 handler，pipeline 必须在 channelCreated 装配。
 */
final class NettyChannelPoolHandler implements ChannelPoolHandler {

    private final boolean tls;
    private final String host;
    private final int port;
    private final NettyHttpConfig config;
    private final SslContext sslContext;

    NettyChannelPoolHandler(boolean tls, String host, int port, NettyHttpConfig config, SslContext sslContext) {
        this.tls = tls;
        this.host = host;
        this.port = port;
        this.config = config;
        this.sslContext = sslContext;
    }

    @Override
    public void channelCreated(Channel ch) {
        ChannelPipeline p = ch.pipeline();
        if (tls) {
            SslHandler ssl = sslContext.newHandler(ch.alloc(), host, port);
            SSLEngine engine = ssl.engine();
            SSLParameters params = engine.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            engine.setSSLParameters(params);
            p.addLast(ssl);
        }
        p.addLast(new HttpClientCodec(16384, 32768, 65536, true));
        p.addLast(new HttpContentDecompressor());
        p.addLast(new IdleStateHandler(0, 0, config.idleTimeoutSec(), TimeUnit.SECONDS));
    }

    @Override
    public void channelReleased(Channel ch) {
        ch.config().setAutoRead(true);
    }

    @Override
    public void channelAcquired(Channel ch) {
        // 无需处理
    }
}

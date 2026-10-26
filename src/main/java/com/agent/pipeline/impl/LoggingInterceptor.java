package com.agent.pipeline.impl;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.InterceptorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 日志拦截器：记录调用开始、结束与失败。
 */
public final class LoggingInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
        log.debug("[{}] -> {} messages={} tools={}", ctx.namespace(), ctx.conversationId(),
                in.messages().size(), in.tools().size());
        return Mono.just(in);
    }

    @Override
    public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
        return events
                .doOnNext(e -> {
                    if (e instanceof ChatEvent.ErrorEvent err) {
                        log.warn("[{}] 错误事件 {}: {}", ctx.namespace(), err.code(), err.message());
                    }
                })
                .doOnError(e -> log.warn("[{}] 调用失败: {}", ctx.namespace(), e.getMessage()))
                .doOnComplete(() -> log.debug("[{}] <- 完成", ctx.namespace()));
    }
}

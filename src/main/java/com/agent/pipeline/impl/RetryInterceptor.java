package com.agent.pipeline.impl;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.InterceptorContext;
import com.agent.resilience.ResilienceManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 重试拦截器：resilience 内核在 executor 层已应用重试（ResilienceManager.guard），
 * 此处是内核在拦截链上的装配形态；内核关闭时兜底启用链上重试。
 */
public final class RetryInterceptor implements Interceptor {

    private final ResilienceManager resilience;

    public RetryInterceptor(ResilienceManager resilience) {
        this.resilience = resilience;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
        return Mono.just(in);
    }

    @Override
    public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
        if (resilience.config().enabled()) {
            return events;   // 内核重试已生效，避免双重重试
        }
        return events.retryWhen(resilience.retryPolicy().toReactor());
    }
}

package com.agent.pipeline.impl;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.InterceptorContext;
import com.agent.resilience.ResilienceManager;
import com.agent.resilience.UserTimeoutGuard;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 超时拦截器：委托 resilience 层 UserTimeoutGuard；内核关闭时兜底在链上应用总超时。
 */
public final class TimeoutInterceptor implements Interceptor {

    private final ResilienceManager resilience;

    public TimeoutInterceptor(ResilienceManager resilience) {
        this.resilience = resilience;
    }

    @Override
    public int order() {
        return 15;
    }

    @Override
    public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
        return Mono.just(in);
    }

    @Override
    public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
        if (resilience.config().enabled()) {
            return events;   // 内核超时已生效
        }
        return new UserTimeoutGuard(resilience.config().requestTimeout()).guard(events);
    }
}

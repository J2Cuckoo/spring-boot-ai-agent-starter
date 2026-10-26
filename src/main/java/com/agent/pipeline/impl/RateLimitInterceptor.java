package com.agent.pipeline.impl;

import com.agent.contract.AgentException;
import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.ErrorCodes;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.InterceptorContext;
import com.agent.resilience.ResilienceManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 限流拦截器：委托 resilience 层 TokenBucket（按模型维度），令牌不足抛 RATE_LIMITED（可重试）。
 */
public final class RateLimitInterceptor implements Interceptor {

    private final ResilienceManager resilience;

    public RateLimitInterceptor(ResilienceManager resilience) {
        this.resilience = resilience;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
        if (resilience.tryAcquirePermit(ctx.namespace())) {
            return Mono.just(in);
        }
        return Mono.error(new AgentException(ErrorCodes.RATE_LIMITED,
                "触发限流: " + ctx.namespace(), true));
    }

    @Override
    public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
        return events;
    }
}

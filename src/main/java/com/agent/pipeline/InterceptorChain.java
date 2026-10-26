package com.agent.pipeline;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;

/**
 * 拦截器链：before 按 order 升序逐个串联；after 按 order 升序逐层包裹
 * （后注册的包在外层）。
 */
public final class InterceptorChain {

    private final List<Interceptor> interceptors;

    public InterceptorChain(List<Interceptor> interceptors) {
        this.interceptors = interceptors.stream()
                .sorted(Comparator.comparingInt(Interceptor::order))
                .toList();
    }

    public List<Interceptor> interceptors() {
        return interceptors;
    }

    public Mono<ChatRequest> before(ChatRequest request, InterceptorContext ctx) {
        Mono<ChatRequest> chain = Mono.just(request);
        for (Interceptor i : interceptors) {
            chain = chain.flatMap(r -> i.before(r, ctx));
        }
        return chain;
    }

    public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
        Flux<ChatEvent> chain = events;
        for (Interceptor i : interceptors) {
            chain = i.after(chain, ctx);
        }
        return chain;
    }
}

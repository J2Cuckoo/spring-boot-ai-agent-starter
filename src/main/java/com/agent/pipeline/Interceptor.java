package com.agent.pipeline;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 拦截器 SPI（有序责任链）：before 修改请求，after 包装事件流。
 * 实现并经 @Bean、Agent.Builder 或 ServiceLoader 注册即被使用。
 */
public interface Interceptor {

    /** 排序值，小者先执行 before、后包装 after；默认 0，仅在需要控制顺序时覆盖。 */
    default int order() {
        return 0;
    }

    default String name() {
        return getClass().getSimpleName();
    }

    Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx);

    Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx);
}

package com.agent.pipeline;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.model.ModelConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterceptorChainTest {

    private static Interceptor interceptor(int order, String tag, List<String> trace) {
        return new Interceptor() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public String name() {
                return tag;
            }

            @Override
            public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
                trace.add("before:" + tag);
                return Mono.just(in.toBuilder()
                        .addMessage(Message.system("[" + tag + "]"))
                        .build());
            }

            @Override
            public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
                trace.add("after:" + tag);
                return events;
            }
        };
    }

    @Test
    void beforeRunsInOrderAfterAlsoInOrder() {
        List<String> trace = new ArrayList<>();
        InterceptorChain chain = new InterceptorChain(List.of(
                interceptor(30, "third", trace),
                interceptor(10, "first", trace),
                interceptor(20, "second", trace)));
        InterceptorContext ctx = new InterceptorContext("openai:m",
                ModelConfig.of("m", "openai", "gpt"), null);

        ChatRequest request = chain.before(ChatRequest.builder()
                .addMessage(Message.user("hi")).build(), ctx).block();
        assertThat(trace).containsExactly("before:first", "before:second", "before:third");
        // 每个 before 注入一条 system 消息
        assertThat(request.messages()).hasSize(4);

        trace.clear();
        List<ChatEvent> events = chain.after(Flux.just(ChatEvent.token("x")), ctx)
                .collectList().block();
        assertThat(events).hasSize(1);
        assertThat(trace).containsExactly("after:first", "after:second", "after:third");
    }
}

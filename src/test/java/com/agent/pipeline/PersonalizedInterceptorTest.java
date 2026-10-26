package com.agent.pipeline;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.model.ModelConfig;
import com.agent.pipeline.impl.HumanizeInterceptor;
import com.agent.pipeline.impl.RagInterceptor;
import com.agent.rag.RagHit;
import com.agent.rag.RagStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 个性化拦截器：内置功能拦截器可被继承并覆盖对应钩子；
 * Interceptor.order() 默认 0，非必须覆盖。
 */
class PersonalizedInterceptorTest {

    private static InterceptorContext ctx(String namespace) {
        return new InterceptorContext(namespace, ModelConfig.of("m", "openai", "gpt"), null);
    }

    @Test
    void orderDefaultsToZeroWhenNotOverridden() {
        Interceptor interceptor = new Interceptor() {
            @Override
            public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
                return Mono.just(in);
            }

            @Override
            public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
                return events;
            }
            // 不覆盖 order()
        };
        assertThat(interceptor.order()).isZero();
    }

    @Test
    void ragInterceptorCanOverrideRetrieve() {
        RagStore unused = new RagStore() {
            public void index(String id, String text) {
            }

            public List<RagHit> search(String q, int k) {
                return List.of();
            }

            public List<RagHit> hybridSearch(String q, int k) {
                return List.of();
            }
        };
        RagInterceptor custom = new RagInterceptor(unused, 4) {
            @Override
            protected List<RagHit> retrieve(String query, int topK) {
                return List.of(new RagHit("1", "自定义检索结果", 0.9));
            }
        };

        ChatRequest req = custom.before(ChatRequest.builder()
                .addMessage(Message.user("查询")).build(), ctx("openai:m")).block();
        // 注入了自定义检索片段
        assertThat(req.messages().get(0).content()).contains("自定义检索结果");
    }

    @Test
    void humanizeInterceptorCanOverrideRewrite() {
        HumanizeInterceptor custom = new HumanizeInterceptor(true, "openai:gate") {
            @Override
            protected String humanize(String content, InterceptorContext ctx) {
                return "改写后:" + content;
            }
        };

        List<ChatEvent> out = custom.after(
                Flux.just(ChatEvent.token("原始"), new ChatEvent.DoneEvent("stop")),
                ctx("openai:m")).collectList().block();
        assertThat(out).hasSize(2);
        assertThat(((ChatEvent.TokenEvent) out.get(0)).delta()).isEqualTo("改写后:原始");
        assertThat(out.get(1)).isInstanceOf(ChatEvent.DoneEvent.class);
    }
}

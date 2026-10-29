package com.agent.pipeline;

import com.agent.Agent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.memory.MemoryStore;
import com.agent.memory.MemoryTier;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import com.agent.pipeline.impl.MemoryInterceptor;
import com.agent.pipeline.impl.RagInterceptor;
import com.agent.rag.RagHit;
import com.agent.rag.RagOptions;
import com.agent.rag.RagStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求级 .rag()/.memory() 选项：经 InterceptorContext 传递给功能拦截器，
 * 覆盖全局默认（maxResults/minScore/tier/namespace/enabled）。
 */
class RagMemoryOptionsTest {

    private static InterceptorContext ctx(String namespace) {
        InterceptorContext c = new InterceptorContext(namespace, ModelConfig.of("m", "openai", "gpt"), null);
        c.attributes().put("memory.userPrompt", "用户问题");
        return c;
    }

    /** 记录实际 topK 的假 RagStore。 */
    private static RagStore recordingStore(AtomicInteger capturedTopK) {
        return new RagStore() {
            public void index(String id, String text) {
            }

            public List<RagHit> search(String q, int k) {
                return List.of();
            }

            public List<RagHit> hybridSearch(String q, int k) {
                capturedTopK.set(k);
                return List.of(
                        new RagHit("1", "高分命中", 0.9),
                        new RagHit("2", "低分命中", 0.2));
            }
        };
    }

    @Test
    void ragMaxResultsOverridesGlobalTopK() {
        AtomicInteger topK = new AtomicInteger();
        RagInterceptor interceptor = new RagInterceptor(recordingStore(topK), 4);

        InterceptorContext c = ctx("openai:m");
        c.attr(RagInterceptor.OPTIONS_KEY, new RagOptions(2, null));   // .rag().maxResults(2)

        ChatRequest req = interceptor.before(ChatRequest.builder()
                .addMessage(Message.user("查询")).build(), c).block();
        // 检索用了请求级 maxResults=2（而非全局 4）
        assertThat(topK.get()).isEqualTo(2);
        assertThat(req.messages().get(0).content()).contains("知识库召回");
    }

    @Test
    void ragMinScoreFiltersLowHits() {
        AtomicInteger topK = new AtomicInteger();
        RagInterceptor interceptor = new RagInterceptor(recordingStore(topK), 4);

        InterceptorContext c = ctx("openai:m");
        c.attr(RagInterceptor.OPTIONS_KEY, new RagOptions(null, 0.5));   // .rag().minScore(0.5)

        ChatRequest req = interceptor.before(ChatRequest.builder()
                .addMessage(Message.user("查询")).build(), c).block();
        String injected = req.messages().get(0).content();
        assertThat(injected).contains("高分命中");
        assertThat(injected).doesNotContain("低分命中");
    }

    @Test
    void ragQueryAndFormatHooksOverridable() {
        RagInterceptor custom = new RagInterceptor(recordingStore(new AtomicInteger()), 4) {
            @Override
            protected String buildQuery(ChatRequest in, InterceptorContext ctx) {
                return "自定义检索词";
            }

            @Override
            protected String formatHits(List<RagHit> hits) {
                return "[自定义模板] " + hits.size() + " 条";
            }
        };
        InterceptorContext c = ctx("openai:m");
        ChatRequest req = custom.before(ChatRequest.builder()
                .addMessage(Message.user("原始问题")).build(), c).block();
        assertThat(req.messages().get(0).content()).contains("[自定义模板] 2 条");
    }

    /** 记录命名空间与层级的假记忆存储。 */
    private static MemoryStore recordingMemory(AtomicReference<String> ns,
                                               java.util.Map<MemoryTier, AtomicInteger> tierHits) {
        return new MemoryStore() {
            public void save(String namespace, String key, Object data, MemoryTier tier) {
            }

            public <T> java.util.Optional<T> load(String namespace, String key, Class<T> type, MemoryTier tier) {
                ns.set(namespace);
                tierHits.computeIfAbsent(tier, t -> new AtomicInteger()).incrementAndGet();
                if (tier == MemoryTier.LONG_TERM) {
                    return java.util.Optional.of(type.cast("长期记忆内容"));
                }
                return java.util.Optional.empty();
            }

            public void forget(String namespace, String key, MemoryTier tier) {
            }
        };
    }

    @Test
    void memoryTierLimitsLookupAndNamespaceOverrides() {
        AtomicReference<String> ns = new AtomicReference<>();
        java.util.Map<MemoryTier, AtomicInteger> tierHits = new java.util.concurrent.ConcurrentHashMap<>();
        MemoryInterceptor interceptor = new MemoryInterceptor(recordingMemory(ns, tierHits), null);

        InterceptorContext c = ctx("openai:m");
        c.attr(MemoryInterceptor.OPTIONS_KEY,
                new com.agent.memory.MemoryOptions(null, MemoryTier.LONG_TERM, "custom-ns"));

        ChatRequest req = interceptor.before(ChatRequest.builder()
                .addMessage(Message.user("hi")).build(), c).block();
        // 只查了 LONG_TERM 一层，且命名空间被请求级覆盖
        assertThat(tierHits.keySet()).containsExactly(MemoryTier.LONG_TERM);
        assertThat(ns.get()).isEqualTo("custom-ns");
        assertThat(req.messages().get(0).content()).contains("长期记忆内容");
    }

    @Test
    void memoryDisabledPerCallSkipsInjection() {
        AtomicReference<String> ns = new AtomicReference<>();
        java.util.Map<MemoryTier, AtomicInteger> tierHits = new java.util.concurrent.ConcurrentHashMap<>();
        MemoryInterceptor interceptor = new MemoryInterceptor(recordingMemory(ns, tierHits), null);

        InterceptorContext c = ctx("openai:m");
        c.attr(MemoryInterceptor.OPTIONS_KEY,
                new com.agent.memory.MemoryOptions(false, null, null));   // .memory().enabled(false)

        ChatRequest req = interceptor.before(ChatRequest.builder()
                .addMessage(Message.user("hi")).build(), c).block();
        assertThat(tierHits).isEmpty();   // 未触发任何记忆读取
        assertThat(req.messages()).hasSize(1);   // 未注入 system
    }

    @Test
    void ragSettingsDslBuildsOptions() {
        // DSL 层：.rag().maxResults(5).minScore(0.3) → RagOptions(5, 0.3)
        Agent agent = Agent.builder()
                .registerProvider(Provider.openai("k"))
                .registerModel(ModelConfig.of("m", "openai", "gpt"))
                .defaultModel("openai:m")
                .build();
        try {
            com.agent.output.Conversation c = agent.chat("q")
                    .rag().maxResults(5).minScore(0.3).end();
            RagOptions options = c.ragOptions();
            assertThat(options.maxResults()).isEqualTo(5);
            assertThat(options.minScore()).isEqualTo(0.3);

            // consumer 形式
            com.agent.output.Conversation c2 = agent.chat("q")
                    .rag(r -> r.maxResults(9));
            assertThat(c2.ragOptions().maxResults()).isEqualTo(9);

            // memory DSL
            com.agent.output.Conversation c3 = agent.chat("q")
                    .memory(m -> m.tier(MemoryTier.SHORT_TERM).namespace("ns-x"));
            com.agent.memory.MemoryOptions mo = c3.memoryOptions();
            assertThat(mo.tier()).isEqualTo(MemoryTier.SHORT_TERM);
            assertThat(mo.namespace()).isEqualTo("ns-x");
        } finally {
            agent.close();
        }
    }
}

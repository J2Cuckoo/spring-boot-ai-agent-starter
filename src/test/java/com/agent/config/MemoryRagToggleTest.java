package com.agent.config;

import com.agent.embedding.EmbeddingModel;
import com.agent.memory.MemoryStore;
import com.agent.rag.RagStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 记忆 / RAG 开关验证：
 * - 记忆默认开启（有 Redis 即装配），memory.enabled=false 可显式关闭；
 * - RAG 默认关闭，须显式 rag.enabled=true 才装配。
 */
class MemoryRagToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withPropertyValues(
                    "spring.agent.default-model=openai:main",
                    "spring.agent.providers.openai.type=OPENAI",
                    "spring.agent.providers.openai.base-url=http://localhost:18080",
                    "spring.agent.providers.openai.api-key=k",
                    "spring.agent.models.main.provider=openai",
                    "spring.agent.models.main.model-name=gpt")
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));

    @Test
    void memoryEnabledByDefaultWhenRedisPresent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(MemoryStore.class));
    }

    @Test
    void memoryCanBeExplicitlyDisabled() {
        runner.withPropertyValues("spring.agent.memory.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(MemoryStore.class);
                    assertThat(ctx).doesNotHaveBean(com.agent.pipeline.impl.MemoryInterceptor.class);
                });
    }

    @Test
    void ragDisabledByDefaultEvenWithEmbeddingConfigured() {
        runner.withPropertyValues(
                        "spring.agent.embedding.provider=ollama",
                        "spring.agent.embedding.model=test-emb",
                        "spring.agent.providers.ollama.type=OLLAMA",
                        "spring.agent.providers.ollama.base-url=http://localhost:11434",
                        "spring.agent.rag.type=milvus")
                .run(ctx -> {
                    // Embedding 已装配（满足 RAG 前置条件），但 rag.enabled 默认 false → RagStore 不装配
                    assertThat(ctx).hasSingleBean(EmbeddingModel.class);
                    assertThat(ctx).doesNotHaveBean(RagStore.class);
                });
    }

    @Test
    void memoryDisabledWithoutRedisStillNoMemoryStore() {
        // 无 Redis Bean + 默认 enabled=true → 依赖缺失自动降级（不报错）
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                .withPropertyValues(
                        "spring.agent.default-model=openai:main",
                        "spring.agent.providers.openai.type=OPENAI",
                        "spring.agent.providers.openai.base-url=http://localhost:18080",
                        "spring.agent.providers.openai.api-key=k",
                        "spring.agent.models.main.provider=openai",
                        "spring.agent.models.main.model-name=gpt")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MemoryStore.class));
    }
}

package com.agent.config;

import com.agent.Agent;
import com.agent.model.ModelRegistry;
import com.agent.model.ProviderRegistry;
import com.agent.model.ProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 宿主真实配置兼容性回归：逐字复刻宿主新的列表式 providers 配置
 * （type 写厂商名、provider 级 max-tokens、字符串形式内嵌 models）。
 */
class HostConfigCompatTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withPropertyValues(
                    // ===== 与宿主 application.yaml 等价（properties 形式） =====
                    "spring.agent.memory.enabled=false",
                    "spring.agent.memory.reverse-optimize=true",
                    "spring.agent.default-model=deepseek:deepseek-v4-flash",
                    "spring.agent.providers[0].deepseek.type=deepseek",
                    "spring.agent.providers[0].deepseek.base-url=https://api.deepseek.com/v1",
                    "spring.agent.providers[0].deepseek.api-key=sk-replaced",
                    "spring.agent.providers[0].deepseek.max-tokens=4096",
                    "spring.agent.providers[0].deepseek.models[0]=deepseek-v4-flash",
                    "spring.agent.providers[0].deepseek.models[1]=deepseek-v4-pro",
                    "spring.agent.providers[1].openai.type=OPENAI",
                    "spring.agent.providers[1].openai.base-url=https://api.openai.com/v1",
                    "spring.agent.providers[1].openai.api-key=${OPENAI_API_KEY}",
                    "spring.agent.providers[1].openai.models[0]=gpt-4o-mini");

    @Test
    void hostConfigStartsAndResolvesDeepSeek() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();

            ProviderRegistry providers = ctx.getBean(ProviderRegistry.class);
            // type: deepseek 未识别，按 Chat Completions 兼容格式
            assertThat(providers.require("deepseek").type())
                    .isEqualTo(ProviderType.OPENAI_CHAT_COMPLETIONS);
            assertThat(providers.require("openai").type())
                    .isEqualTo(ProviderType.OPENAI_CHAT_COMPLETIONS);

            // 内嵌 models 全部注册，继承 provider 级 max-tokens
            ModelRegistry models = ctx.getBean(ModelRegistry.class);
            assertThat(models.resolve("deepseek:deepseek-v4-flash").modelName())
                    .isEqualTo("deepseek-v4-flash");
            assertThat(models.resolve("deepseek:deepseek-v4-flash").maxTokens()).isEqualTo(4096);
            assertThat(models.resolve("deepseek:deepseek-v4-pro").modelName())
                    .isEqualTo("deepseek-v4-pro");
            assertThat(models.resolve("openai:gpt-4o-mini").modelName()).isEqualTo("gpt-4o-mini");

            // Agent 装配成功，default-model 可解析
            Agent agent = ctx.getBean(Agent.class);
            assertThat(agent.resolvedRef(agent.chat("hi"))).isEqualTo("deepseek:deepseek-v4-flash");

            // 记忆关闭：即使 Redis 存在也不装配
            assertThat(ctx).doesNotHaveBean(com.agent.memory.MemoryStore.class);
            assertThat(ctx).doesNotHaveBean(com.agent.pipeline.impl.MemoryInterceptor.class);
        });
    }

    @Test
    void objectFormModelsSupported() {
        // 对象形式内嵌模型（追加 models[2]）：完整配置覆盖 provider 默认
        runner.withPropertyValues(
                        "spring.agent.providers[1].openai.models[2].name=mini",
                        "spring.agent.providers[1].openai.models[2].model-name=gpt-4o",
                        "spring.agent.providers[1].openai.models[2].temperature=0.5")
                .run(ctx -> {
                    ModelRegistry models = ctx.getBean(ModelRegistry.class);
                    assertThat(models.tryResolve("openai:mini").orElseThrow().modelName())
                            .isEqualTo("gpt-4o");
                    assertThat(models.tryResolve("openai:mini").orElseThrow().temperature())
                            .isEqualTo(0.5);
                    // 字符串形式仍在
                    assertThat(models.tryResolve("openai:gpt-4o-mini").orElseThrow().modelName())
                            .isEqualTo("gpt-4o-mini");
                });
    }
}

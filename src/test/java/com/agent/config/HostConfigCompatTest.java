package com.agent.config;

import com.agent.Agent;
import com.agent.model.ProviderRegistry;
import com.agent.model.ProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 宿主真实配置兼容性回归：逐字复刻宿主 application.yaml 的 spring.agent.* 段
 * （含 memory.enabled=false、type: deepseek、别名含连字符的模型）。
 */
class HostConfigCompatTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            // 宿主自带的 Redis（jedis pool 配置属宿主侧，与 SDK 无关；这里只放模板 Bean 模拟“Redis 存在”）
            .withBean(StringRedisTemplate.class, () -> org.mockito.Mockito.mock(StringRedisTemplate.class))
            .withPropertyValues(
                    // ===== 与宿主 application.yaml 完全一致 =====
                    "spring.agent.memory.enabled=false",
                    "spring.agent.memory.reverse-optimize=true",
                    "spring.agent.default-model=deepseek:deepseek-v4-flash",
                    "spring.agent.providers.deepseek.type=deepseek",
                    "spring.agent.providers.deepseek.base-url=https://api.deepseek.com/v1",
                    "spring.agent.providers.deepseek.api-key=sk-replaced",
                    "spring.agent.models.deepseek-v4-flash.provider=deepseek",
                    "spring.agent.models.deepseek-v4-flash.model-name=deepseek-v4-flash");

    @Test
    void hostConfigStartsAndResolvesDeepSeek() {
        runner.run(ctx -> {
            // 1) 上下文正常启动（type: deepseek 不再炸启动）
            assertThat(ctx).hasNotFailed();

            // 2) deepseek provider 按未识别厂商名归一化为 CUSTOM（OpenAI 兼容协议）
            ProviderRegistry providers = ctx.getBean(ProviderRegistry.class);
            assertThat(providers.require("deepseek").type()).isEqualTo(ProviderType.CUSTOM);

            // 3) Agent 装配成功，default-model（别名含连字符）可解析
            assertThat(ctx).hasSingleBean(Agent.class);
            Agent agent = ctx.getBean(Agent.class);
            assertThat(agent.resolvedRef(agent.chat("hi")))
                    .isEqualTo("deepseek:deepseek-v4-flash");

            // 4) 记忆关闭：即使 Redis 存在，也不装配记忆存储与拦截器
            assertThat(ctx).doesNotHaveBean(com.agent.memory.MemoryStore.class);
            assertThat(ctx).doesNotHaveBean(com.agent.pipeline.impl.MemoryInterceptor.class);
        });
    }
}

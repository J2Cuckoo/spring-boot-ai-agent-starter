package com.agent.config;

import com.agent.Agent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 零配置启动：宿主不写任何 spring.agent.* 配置，context 必须能正常装配；
 * 模型解析推迟到调用期，报错带配置指引。
 */
class ZeroConfigStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class));

    @Test
    void contextStartsWithoutAnyAgentProperties() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(Agent.class);
            assertThat(ctx).hasSingleBean(com.agent.model.ModelRegistry.class);
            assertThat(ctx).hasSingleBean(com.agent.model.ProviderRegistry.class);
            assertThat(ctx).hasSingleBean(com.agent.skill.SkillManager.class);
            assertThat(ctx).hasSingleBean(com.agent.util.AgentHolder.class);
            assertThat(ctx).hasSingleBean(com.agent.transport.NettyHttpClient.class);
            assertThat(ctx).hasSingleBean(com.agent.resilience.ResilienceManager.class);
            // 无 Redis 依赖时记忆自动降级，不阻断装配
            assertThat(ctx).doesNotHaveBean(com.agent.memory.MemoryStore.class);
        });
    }

    @Test
    void chatWithoutModelFailsAtCallTimeWithGuidance() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            Agent agent = ctx.getBean(Agent.class);
            assertThatThrownBy(() -> agent.resolvedRef(agent.chat("hi")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.agent.default-model");
        });
    }
}

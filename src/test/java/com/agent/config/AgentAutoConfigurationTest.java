package com.agent.config;

import com.agent.Agent;
import com.agent.model.ModelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withPropertyValues(
                    "spring.agent.default-model=openai:main",
                    "spring.agent.providers.openai.type=OPENAI",
                    "spring.agent.providers.openai.base-url=http://localhost:18080",
                    "spring.agent.providers.openai.api-key=test-key",
                    "spring.agent.models.main.provider=openai",
                    "spring.agent.models.main.model-name=gpt-test",
                    "spring.agent.models.main.temperature=0.7",
                    "spring.agent.memory.database=15",
                    "spring.agent.memory.cache.ttl=30m",
                    "spring.agent.memory.cache.max-size=20MB");

    @Test
    void bindsHostConfigAndAssemblesBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(Agent.class);
            assertThat(ctx).hasSingleBean(ModelRegistry.class);
            assertThat(ctx).hasSingleBean(com.agent.transport.NettyHttpClient.class);
            assertThat(ctx).hasSingleBean(com.agent.skill.SkillManager.class);
            assertThat(ctx).hasSingleBean(com.agent.pipeline.impl.SkillInjectInterceptor.class);
            assertThat(ctx).doesNotHaveBean(com.agent.memory.MemoryStore.class);   // 无 Redis，降级关闭
            assertThat(ctx).doesNotHaveBean(com.agent.pipeline.impl.MemoryInterceptor.class);

            ModelRegistry registry = ctx.getBean(ModelRegistry.class);
            assertThat(registry.resolve("openai:main").modelName()).isEqualTo("gpt-test");
            assertThat(registry.resolve("openai:main").temperature()).isEqualTo(0.7);

            Agent agent = ctx.getBean(Agent.class);
            // 四种终结方式链路装配：chat 不触发调用；注册表贯通到 Agent
            assertThat(agent.chat("hi")).isNotNull();
            assertThat(agent.resolvedRef(agent.chat("hi"))).isEqualTo("openai:main");
        });
    }

    @Test
    void humanGateOffByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(com.agent.pipeline.impl.HumanizeInterceptor.class));
    }

    @Test
    void humanGateOnRequiresModel() {
        runner.withPropertyValues(
                        "spring.agent.gate.human-gate=true",
                        "spring.agent.gate.model=openai:main")
                .run(ctx -> assertThat(ctx).hasSingleBean(com.agent.pipeline.impl.HumanizeInterceptor.class));
    }
}

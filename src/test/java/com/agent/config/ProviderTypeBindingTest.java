package com.agent.config;

import com.agent.model.ProviderRegistry;
import com.agent.model.ProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复现宿主真实场景：第三方 OpenAI 兼容厂商（如 deepseek）直接写 type: deepseek。
 * 修复前：枚举绑定失败导致应用启动崩溃；
 * 修复后：按 CUSTOM（OpenAI 兼容协议）处理，启动正常。
 */
class ProviderTypeBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class));

    @Test
    void unknownVendorTypeBindsAsCustomAndAppStarts() {
        // 与宿主报错配置一致：type: deepseek
        runner.withPropertyValues(
                        "spring.agent.default-model=deepseek:chat",
                        "spring.agent.providers.deepseek.type=deepseek",
                        "spring.agent.providers.deepseek.base-url=https://api.deepseek.com",
                        "spring.agent.providers.deepseek.api-key=sk-x",
                        "spring.agent.models.chat.provider=deepseek",
                        "spring.agent.models.chat.model-name=deepseek-chat")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(ProviderRegistry.class);
                    ProviderRegistry registry = ctx.getBean(ProviderRegistry.class);
                    assertThat(registry.require("deepseek").type()).isEqualTo(ProviderType.CUSTOM);
                });
    }

    @Test
    void lowercaseEnumValueIsAccepted() {
        runner.withPropertyValues(
                        "spring.agent.providers.my.type=openai",
                        "spring.agent.providers.my.base-url=http://localhost:1",
                        "spring.agent.providers.my.api-key=k")
                .run(ctx -> assertThat(ctx.getBean(ProviderRegistry.class).require("my").type())
                        .isEqualTo(ProviderType.OPENAI));
    }

    @Test
    void missingTypeDefaultsToCustom() {
        runner.withPropertyValues(
                        "spring.agent.providers.my.base-url=http://localhost:1",
                        "spring.agent.providers.my.api-key=k")
                .run(ctx -> assertThat(ctx.getBean(ProviderRegistry.class).require("my").type())
                        .isEqualTo(ProviderType.CUSTOM));
    }

    @Test
    void normalizeHelper() {
        assertThat(AgentAutoConfiguration.normalizeProviderType("OPENAI", "a")).isEqualTo(ProviderType.OPENAI);
        assertThat(AgentAutoConfiguration.normalizeProviderType(" Claude ", "a")).isEqualTo(ProviderType.CLAUDE);
        assertThat(AgentAutoConfiguration.normalizeProviderType("ollama", "a")).isEqualTo(ProviderType.OLLAMA);
        assertThat(AgentAutoConfiguration.normalizeProviderType(null, "a")).isEqualTo(ProviderType.CUSTOM);
        assertThat(AgentAutoConfiguration.normalizeProviderType("deepseek", "a")).isEqualTo(ProviderType.CUSTOM);
    }
}

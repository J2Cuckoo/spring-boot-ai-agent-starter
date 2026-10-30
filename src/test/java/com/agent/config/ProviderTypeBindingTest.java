package com.agent.config;

import com.agent.model.ProviderRegistry;
import com.agent.model.ProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * type 按协议格式解析（chat-completions / responses / anthropic-messages 等），
 * providers 为列表结构且模型内嵌。
 */
class ProviderTypeBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class));

    @Test
    void unknownVendorTypeBindsAsChatCompletions() {
        runner.withPropertyValues(
                        "spring.agent.default-model=deepseek:deepseek-chat",
                        "spring.agent.providers[0].deepseek.type=deepseek",
                        "spring.agent.providers[0].deepseek.base-url=https://api.deepseek.com",
                        "spring.agent.providers[0].deepseek.api-key=sk-x",
                        "spring.agent.providers[0].deepseek.models[0]=deepseek-chat")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ProviderRegistry.class).require("deepseek").type())
                            .isEqualTo(ProviderType.OPENAI_CHAT_COMPLETIONS);
                    // 字符串形式模型：别名=真实名
                    assertThat(ctx.getBean(com.agent.model.ModelRegistry.class)
                            .resolve("deepseek:deepseek-chat").modelName()).isEqualTo("deepseek-chat");
                });
    }

    @Test
    void protocolFormatValuesAccepted() {
        runner.withPropertyValues(
                        "spring.agent.providers[0].a.type=chat-completions",
                        "spring.agent.providers[0].a.base-url=http://l:1",
                        "spring.agent.providers[0].b.type=responses",
                        "spring.agent.providers[0].b.base-url=http://l:1",
                        "spring.agent.providers[0].c.type=anthropic-messages",
                        "spring.agent.providers[0].c.base-url=http://l:1",
                        "spring.agent.providers[0].d.type=gemini",
                        "spring.agent.providers[0].d.base-url=http://l:1",
                        "spring.agent.providers[0].e.type=ollama",
                        "spring.agent.providers[0].e.base-url=http://l:11434")
                .run(ctx -> {
                    ProviderRegistry r = ctx.getBean(ProviderRegistry.class);
                    assertThat(r.require("a").type()).isEqualTo(ProviderType.OPENAI_CHAT_COMPLETIONS);
                    assertThat(r.require("b").type()).isEqualTo(ProviderType.OPENAI_RESPONSES);
                    assertThat(r.require("c").type()).isEqualTo(ProviderType.ANTHROPIC_MESSAGES);
                    assertThat(r.require("d").type()).isEqualTo(ProviderType.GEMINI);
                    assertThat(r.require("e").type()).isEqualTo(ProviderType.OLLAMA);
                });
    }

    @Test
    void legacyValuesStillMap() {
        runner.withPropertyValues(
                        "spring.agent.providers[0].a.type=OPENAI",
                        "spring.agent.providers[0].a.base-url=http://l:1",
                        "spring.agent.providers[0].b.type=CLAUDE",
                        "spring.agent.providers[0].b.base-url=http://l:1")
                .run(ctx -> {
                    ProviderRegistry r = ctx.getBean(ProviderRegistry.class);
                    assertThat(r.require("a").type()).isEqualTo(ProviderType.OPENAI_CHAT_COMPLETIONS);
                    assertThat(r.require("b").type()).isEqualTo(ProviderType.ANTHROPIC_MESSAGES);
                });
    }

    @Test
    void missingTypeDefaultsToChatCompletions() {
        runner.withPropertyValues(
                        "spring.agent.providers[0].my.base-url=http://l:1",
                        "spring.agent.providers[0].my.api-key=k")
                .run(ctx -> assertThat(ctx.getBean(ProviderRegistry.class).require("my").type())
                        .isEqualTo(ProviderType.OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void normalizeHelper() {
        assertThat(AgentAutoConfiguration.normalizeProviderType("chat-completions", "a"))
                .isEqualTo(ProviderType.OPENAI_CHAT_COMPLETIONS);
        assertThat(AgentAutoConfiguration.normalizeProviderType("OPENAI_RESPONSES", "a"))
                .isEqualTo(ProviderType.OPENAI_RESPONSES);
        assertThat(AgentAutoConfiguration.normalizeProviderType(" anthropic_messages ", "a"))
                .isEqualTo(ProviderType.ANTHROPIC_MESSAGES);
        assertThat(AgentAutoConfiguration.normalizeProviderType(null, "a"))
                .isEqualTo(ProviderType.OPENAI_CHAT_COMPLETIONS);
        assertThat(AgentAutoConfiguration.normalizeProviderType("deepseek", "a"))
                .isEqualTo(ProviderType.OPENAI_CHAT_COMPLETIONS);
    }
}

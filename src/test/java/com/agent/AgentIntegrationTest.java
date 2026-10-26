package com.agent;

import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试：依赖真实网络与 API Key。
 * 默认（surefire excludedGroups=integration）不执行；
 * 显式触发：mvn verify -Dgroups=integration。Key 缺失时自动跳过。
 */
@Tag("integration")
class AgentIntegrationTest {

    @Test
    void openaiWriteRoundTrip() {
        String key = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "OPENAI_API_KEY 未设置，跳过");
        Agent agent = Agent.builder()
                .registerProvider(Provider.openai(key))
                .registerModel(ModelConfig.of("mini", "openai", "gpt-4o-mini"))
                .defaultModel("openai:mini")
                .build();
        try {
            String out = agent.write("只回复两个字：你好");
            assertThat(out).isNotBlank();
        } finally {
            agent.close();
        }
    }

    @Test
    void anthropicWriteRoundTrip() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "ANTHROPIC_API_KEY 未设置，跳过");
        Agent agent = Agent.builder()
                .registerProvider(Provider.claude(key))
                .registerModel(ModelConfig.builder("sonnet", "anthropic", "claude-sonnet-4-5")
                        .maxTokens(256)
                        .build())
                .defaultModel("anthropic:sonnet")
                .build();
        try {
            String out = agent.write("只回复两个字：你好");
            assertThat(out).isNotBlank();
        } finally {
            agent.close();
        }
    }
}

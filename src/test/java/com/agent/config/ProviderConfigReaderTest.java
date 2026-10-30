package com.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConfigReaderTest {

    @Test
    void readsListProvidersWithEmbeddedModels() {
        StandardEnvironment env = new StandardEnvironment();
        TestPropertyValues.of(
                        "spring.agent.providers[0].deepseek.type=deepseek",
                        "spring.agent.providers[0].deepseek.base-url=https://api.deepseek.com",
                        "spring.agent.providers[0].deepseek.max-tokens=4096",
                        "spring.agent.providers[0].deepseek.models[0]=deepseek-v4-flash",
                        "spring.agent.providers[0].deepseek.models[1]=deepseek-v4-pro",
                        "spring.agent.providers[1].openai.type=OPENAI",
                        "spring.agent.providers[1].openai.models[0]=gpt-4o-mini",
                        "spring.agent.providers[1].openai.models[1].name=mini",
                        "spring.agent.providers[1].openai.models[1].model-name=gpt-4o")
                .applyTo(env);
        Map<String, Map<String, Object>> providers = ProviderConfigReader.readProviders(env);
        env.getPropertySources().stream()
                .filter(ps -> ps instanceof org.springframework.core.env.EnumerablePropertySource<?> e)
                .forEach(ps -> System.out.println("SRC=" + ps.getName() + " KEYS="
                        + java.util.Arrays.toString(((org.springframework.core.env.EnumerablePropertySource<?>) ps).getPropertyNames())));
        System.out.println("PROVIDERS=" + providers);
        assertThat(providers).containsKeys("deepseek", "openai");
        assertThat(providers.get("deepseek").get("models"))
                .asList().hasSize(2).contains("deepseek-v4-flash");
        assertThat(providers.get("openai").get("models")).asList().hasSize(2);
    }
}

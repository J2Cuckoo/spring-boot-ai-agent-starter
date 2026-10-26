package com.agent.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRegistryTest {

    @Test
    void registerAndResolveTwoDimension() {
        ModelRegistry registry = new ModelRegistry();
        registry.registerProvider(Provider.openai("k1"));
        registry.registerProvider(Provider.ollama("http://localhost:11434"));
        registry.registerModel(ModelConfig.of("fast", "openai", "gpt-x"));
        registry.registerModel(ModelConfig.of("slow", "openai", "gpt-4o"));
        registry.registerModel(ModelConfig.of("local", "ollama", "llama3:8b"));

        assertThat(registry.resolve("openai", "fast").modelName()).isEqualTo("gpt-x");
        assertThat(registry.resolve("openai:slow").modelName()).isEqualTo("gpt-4o");
        // Ollama 别名含冒号：按第一个 : 分割
        assertThat(registry.resolve("ollama:local").modelName()).isEqualTo("llama3:8b");

        Map<String, ModelConfig> openaiModels = registry.models("openai");
        assertThat(openaiModels).containsKeys("fast", "slow").hasSize(2);
    }

    @Test
    void unknownModelThrows() {
        ModelRegistry registry = new ModelRegistry();
        registry.registerProvider(Provider.openai("k1"));
        assertThatThrownBy(() -> registry.resolve("openai:none"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册的模型");
    }

    @Test
    void modelWithoutProviderThrows() {
        ModelRegistry registry = new ModelRegistry();
        assertThatThrownBy(() -> registry.registerModel(ModelConfig.of("m", "ghost", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册的 provider");
    }

    @Test
    void illegalRefRejected() {
        ModelRegistry registry = new ModelRegistry();
        assertThatThrownBy(() -> registry.resolve("no-colon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法模型引用");
    }
}

package com.agent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRefTest {

    @Test
    void parseProviderModel() {
        ModelRef ref = ModelRef.of("openai:my-model");
        assertThat(ref.provider()).isEqualTo("openai");
        assertThat(ref.model()).isEqualTo("my-model");
        assertThat(ref.ref()).isEqualTo("openai:my-model");
    }

    @Test
    void ollamaTagWithColon() {
        ModelRef ref = ModelRef.of("ollama:llama3:8b");
        assertThat(ref.provider()).isEqualTo("ollama");
        assertThat(ref.model()).isEqualTo("llama3:8b");
    }

    @Test
    void twoArgFactory() {
        ModelRef ref = ModelRef.of("openai", "gpt-4o");
        assertThat(ref.ref()).isEqualTo("openai:gpt-4o");
    }

    @Test
    void invalidRefRejected() {
        assertThatThrownBy(() -> ModelRef.of("no-colon"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelRef.of("", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

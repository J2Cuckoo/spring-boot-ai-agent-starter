package com.agent.embedding;

/**
 * 向量化配置：{@code spring.agent.embedding.*}。
 */
public record EmbeddingConfig(String provider, String model, int dimensions) {

    public EmbeddingConfig {
        provider = provider == null || provider.isBlank() ? "ollama" : provider;
        dimensions = dimensions <= 0 ? 768 : dimensions;
    }
}

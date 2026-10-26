package com.agent.embedding;

/**
 * 向量化 SPI：RAG 的向量化依赖，可 @Bean / ServiceLoader 替换。
 */
public interface EmbeddingModel {

    float[] embed(String text);

    int dimensions();
}

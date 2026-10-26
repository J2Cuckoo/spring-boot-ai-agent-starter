package com.agent.rag;

import java.util.List;

/**
 * 外挂 RAG SPI（签名稳定）。向量化由内部 {@code EmbeddingModel} 完成。
 * 实现并注册（@Bean 或 ServiceLoader）即被内置 RagInterceptor 使用。
 */
public interface RagStore {

    void index(String id, String text);

    List<RagHit> search(String query, int topK);

    List<RagHit> hybridSearch(String query, int topK);
}

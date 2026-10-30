package com.agent.rag;

/**
 * RAG 配置：{@code spring.agent.rag.*}，含 Milvus 连接池参数。
 */
public record RagConfig(String type, String host, int port, String collection, int topK,
                        int poolMaxSize, long poolAcquireTimeoutMs) {

    public RagConfig {
        type = type == null || type.isBlank() ? "milvus" : type;
        host = host == null || host.isBlank() ? "localhost" : host;
        port = port <= 0 ? 19530 : port;
        collection = collection == null || collection.isBlank() ? "agent-memory" : collection;
        topK = topK <= 0 ? 4 : topK;
        poolMaxSize = poolMaxSize <= 0 ? 4 : poolMaxSize;
        poolAcquireTimeoutMs = poolAcquireTimeoutMs <= 0 ? 5000 : poolAcquireTimeoutMs;
    }

    /** 兼容旧构造（默认池参数）。 */
    public RagConfig(String type, String host, int port, String collection, int topK) {
        this(type, host, port, collection, topK, 4, 5000);
    }
}

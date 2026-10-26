package com.agent.rag;

/**
 * RAG 配置：{@code spring.agent.rag.*}。
 */
public record RagConfig(String type, String host, int port, String collection, int topK) {

    public RagConfig {
        type = type == null || type.isBlank() ? "milvus" : type;
        host = host == null || host.isBlank() ? "localhost" : host;
        port = port <= 0 ? 19530 : port;
        collection = collection == null || collection.isBlank() ? "agent-memory" : collection;
        topK = topK <= 0 ? 4 : topK;
    }
}

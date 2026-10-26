package com.agent.embedding;

import com.agent.model.Provider;
import com.agent.protocol.openai.OpenAIAdapter;
import com.agent.serialize.JacksonConfig;
import com.agent.transport.HttpRequestSpec;
import com.agent.transport.HttpResponse;
import com.agent.transport.NettyHttpClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * Ollama 本地向量化（POST /api/embeddings，同步阻塞语义）。
 */
public final class OllamaEmbeddingModel implements EmbeddingModel {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final NettyHttpClient http;
    private final Provider provider;
    private final EmbeddingConfig config;
    private volatile int resolvedDimensions;

    public OllamaEmbeddingModel(NettyHttpClient http, Provider provider, EmbeddingConfig config) {
        this.http = http;
        this.provider = provider;
        this.config = config;
    }

    @Override
    public float[] embed(String text) {
        String body = JacksonConfig.mapper().createObjectNode()
                .put("model", config.model())
                .put("prompt", text)
                .toString();
        HttpRequestSpec spec = HttpRequestSpec.builder()
                .uri(OpenAIAdapter.join(provider.baseUrl(), "/api/embeddings"))
                .body(body)
                .build();
        HttpResponse resp = http.execute(spec).block(TIMEOUT);
        if (resp == null || !resp.ok()) {
            throw new IllegalStateException("Ollama embedding 失败: HTTP "
                    + (resp == null ? "timeout" : resp.status() + " " + resp.bodyText()));
        }
        try {
            JsonNode arr = JacksonConfig.mapper().readTree(resp.body()).path("embedding");
            float[] out = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                out[i] = (float) arr.get(i).asDouble();
            }
            if (resolvedDimensions == 0 && out.length > 0) {
                resolvedDimensions = out.length;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Ollama embedding 响应解析失败", e);
        }
    }

    @Override
    public int dimensions() {
        int d = resolvedDimensions;
        return d > 0 ? d : config.dimensions();
    }
}

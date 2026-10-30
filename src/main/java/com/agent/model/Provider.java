package com.agent.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供商定义：协议类型 + 端点 + 认证。SDK 不内置任何固定 Key 或端点。
 */
public final class Provider {

    private final String name;
    private final ProviderType type;
    private final String baseUrl;
    private final String apiKey;
    private final long timeoutMs;
    private final int maxRetries;
    private final Map<String, String> headers;

    private Provider(Builder b) {
        this.name = b.name;
        this.type = b.type;
        this.baseUrl = b.baseUrl;
        this.apiKey = b.apiKey;
        this.timeoutMs = b.timeoutMs;
        this.maxRetries = b.maxRetries;
        this.headers = b.headers == null ? Map.of() : Map.copyOf(b.headers);
    }

    public static Provider openai(String apiKey) {
        return builder("openai", ProviderType.OPENAI_CHAT_COMPLETIONS)
                .baseUrl("https://api.openai.com/v1")
                .apiKey(apiKey)
                .build();
    }

    public static Provider claude(String apiKey) {
        return builder("anthropic", ProviderType.ANTHROPIC_MESSAGES)
                .baseUrl("https://api.anthropic.com/v1")
                .apiKey(apiKey)
                .build();
    }

    public static Provider gemini(String apiKey) {
        return builder("gemini", ProviderType.GEMINI)
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .apiKey(apiKey)
                .build();
    }

    public static Provider ollama(String baseUrl) {
        return builder("ollama", ProviderType.OLLAMA).baseUrl(baseUrl).build();
    }

    /** 自定义（OpenAI 兼容）提供商的 Builder。 */
    public static Builder custom(String name) {
        return builder(name, ProviderType.OPENAI_CHAT_COMPLETIONS);
    }

    public static Builder builder(String name, ProviderType type) {
        return new Builder(name, type);
    }

    public String name() {
        return name;
    }

    public ProviderType type() {
        return type;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public static final class Builder {

        private final String name;
        private final ProviderType type;
        private String baseUrl;
        private String apiKey;
        private long timeoutMs = 120_000L;
        private int maxRetries;
        private Map<String, String> headers;

        private Builder(String name, ProviderType type) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("provider name 不能为空");
            }
            this.name = name;
            this.type = type == null ? ProviderType.OPENAI_CHAT_COMPLETIONS : type;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Provider build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("provider[" + name + "] 缺少 base-url");
            }
            return new Provider(this);
        }
    }
}

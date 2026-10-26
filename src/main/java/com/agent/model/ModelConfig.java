package com.agent.model;

import java.util.Map;

/**
 * 模型定义：name（本地别名）与 modelName（厂商真实名）分离，
 * 通过 {@code provider:name} 引用。
 */
public final class ModelConfig {

    private final String name;
    private final String provider;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final Boolean stream;
    private final Map<String, Object> extra;
    private final Integer memoryDatabase;   // 覆盖全局 memory.database

    private ModelConfig(Builder b) {
        this.name = b.name;
        this.provider = b.provider;
        this.modelName = b.modelName;
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.stream = b.stream;
        this.extra = b.extra == null ? Map.of() : Map.copyOf(b.extra);
        this.memoryDatabase = b.memoryDatabase;
    }

    public static ModelConfig of(String name, String provider, String modelName) {
        return builder(name, provider, modelName).build();
    }

    public static Builder builder(String name, String provider, String modelName) {
        return new Builder(name, provider, modelName);
    }

    public String name() {
        return name;
    }

    public String provider() {
        return provider;
    }

    public String modelName() {
        return modelName;
    }

    public Double temperature() {
        return temperature;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public Boolean stream() {
        return stream;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    public Integer memoryDatabase() {
        return memoryDatabase;
    }

    public String ref() {
        return provider + ":" + name;
    }

    public static final class Builder {

        private final String name;
        private final String provider;
        private final String modelName;
        private Double temperature;
        private Integer maxTokens;
        private Boolean stream;
        private Map<String, Object> extra;
        private Integer memoryDatabase;

        private Builder(String name, String provider, String modelName) {
            this.name = name;
            this.provider = provider;
            this.modelName = modelName;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder stream(Boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder extra(Map<String, Object> extra) {
            this.extra = extra;
            return this;
        }

        public Builder memoryDatabase(Integer memoryDatabase) {
            this.memoryDatabase = memoryDatabase;
            return this;
        }

        public ModelConfig build() {
            return new ModelConfig(this);
        }
    }
}

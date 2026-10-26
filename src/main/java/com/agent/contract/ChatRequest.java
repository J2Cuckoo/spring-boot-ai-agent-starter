package com.agent.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一请求对象。
 */
public final class ChatRequest {

    private final String model;                 // provider:model 覆盖
    private final List<Message> messages;
    private final Double temperature;
    private final Integer maxTokens;
    private final Boolean stream;
    private final List<ToolSpec> tools;
    private final String responseFormat;        // "json" 时注入 JSON 约束
    private final Map<String, Object> extra;    // 厂商特定参数

    private ChatRequest(Builder b) {
        this.model = b.model;
        this.messages = List.copyOf(b.messages);
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.stream = b.stream;
        this.tools = b.tools == null ? List.of() : List.copyOf(b.tools);
        this.responseFormat = b.responseFormat;
        this.extra = b.extra == null ? Map.of() : Map.copyOf(b.extra);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String model() {
        return model;
    }

    public List<Message> messages() {
        return messages;
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

    public List<ToolSpec> tools() {
        return tools;
    }

    public String responseFormat() {
        return responseFormat;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    public Builder toBuilder() {
        return new Builder()
                .model(model)
                .messages(messages)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .stream(stream)
                .tools(tools)
                .responseFormat(responseFormat)
                .extra(extra);
    }

    public static final class Builder {

        private String model;
        private final List<Message> messages = new ArrayList<>();
        private Double temperature;
        private Integer maxTokens;
        private Boolean stream;
        private List<ToolSpec> tools;
        private String responseFormat;
        private Map<String, Object> extra;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages.clear();
            if (messages != null) {
                this.messages.addAll(messages);
            }
            return this;
        }

        public Builder addMessage(Message message) {
            this.messages.add(message);
            return this;
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

        public Builder tools(List<ToolSpec> tools) {
            this.tools = tools;
            return this;
        }

        public Builder responseFormat(String responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public Builder extra(Map<String, Object> extra) {
            this.extra = extra;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(this);
        }
    }
}

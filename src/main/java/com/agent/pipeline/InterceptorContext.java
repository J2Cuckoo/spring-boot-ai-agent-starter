package com.agent.pipeline;

import com.agent.Agent;
import com.agent.model.ModelConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次调用的拦截上下文：会话 ID、模型命名空间、模型配置、宿主 Agent 引用与扩展属性。
 */
public final class InterceptorContext {

    private final String conversationId = UUID.randomUUID().toString();
    private final String namespace;          // provider:model
    private final ModelConfig modelConfig;
    private final Agent agent;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public InterceptorContext(String namespace, ModelConfig modelConfig, Agent agent) {
        this.namespace = namespace;
        this.modelConfig = modelConfig;
        this.agent = agent;
    }

    public String conversationId() {
        return conversationId;
    }

    public String namespace() {
        return namespace;
    }

    public ModelConfig modelConfig() {
        return modelConfig;
    }

    /** 宿主 Agent（供门禁等需要再调用模型的拦截器使用）。 */
    public Agent agent() {
        return agent;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    @SuppressWarnings("unchecked")
    public <T> T attr(String key) {
        return (T) attributes.get(key);
    }

    public void attr(String key, Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }
}

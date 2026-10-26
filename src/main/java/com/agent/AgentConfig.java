package com.agent;

import com.agent.memory.MemoryConfig;
import com.agent.model.ModelRegistry;
import com.agent.memory.MemoryConsolidator;
import com.agent.memory.MemoryStore;
import com.agent.model.ModelRegistry;
import com.agent.pipeline.Interceptor;
import com.agent.rag.RagStore;
import com.agent.resilience.ResilienceConfig;
import com.agent.skill.SkillManager;
import com.agent.transport.NettyHttpConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局配置：注册表、拦截器、传输/容错配置与可替换存储。
 */
public final class AgentConfig {

    private final ModelRegistry registry;
    private final String defaultModel;
    private final List<Interceptor> interceptors;
    private final NettyHttpConfig transport;
    private final ResilienceConfig resilience;
    private final MemoryStore memoryStore;
    private final MemoryConfig memoryConfig;
    private final MemoryConsolidator memoryConsolidator;
    private final RagStore ragStore;
    private final int ragTopK;
    private final boolean gateEnabled;
    private final String gateModel;
    private final SkillManager skillManager;

    AgentConfig(Builder b) {
        this.registry = b.registry;
        this.defaultModel = b.defaultModel;
        this.interceptors = List.copyOf(b.interceptors);
        this.transport = b.transport;
        this.resilience = b.resilience;
        this.memoryStore = b.memoryStore;
        this.memoryConfig = b.memoryConfig;
        this.memoryConsolidator = b.memoryConsolidator;
        this.ragStore = b.ragStore;
        this.ragTopK = b.ragTopK;
        this.gateEnabled = b.gateEnabled;
        this.gateModel = b.gateModel;
        this.skillManager = b.skillManager;
    }

    public ModelRegistry registry() {
        return registry;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public List<Interceptor> interceptors() {
        return interceptors;
    }

    public NettyHttpConfig transport() {
        return transport;
    }

    public ResilienceConfig resilience() {
        return resilience;
    }

    public MemoryStore memoryStore() {
        return memoryStore;
    }

    public MemoryConfig memoryConfig() {
        return memoryConfig;
    }

    public MemoryConsolidator memoryConsolidator() {
        return memoryConsolidator;
    }

    public RagStore ragStore() {
        return ragStore;
    }

    public int ragTopK() {
        return ragTopK;
    }

    public boolean gateEnabled() {
        return gateEnabled;
    }

    public String gateModel() {
        return gateModel;
    }

    public SkillManager skillManager() {
        return skillManager;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final ModelRegistry registry = new ModelRegistry();
        private String defaultModel;
        private final List<Interceptor> interceptors = new ArrayList<>();
        private NettyHttpConfig transport = NettyHttpConfig.defaults();
        private ResilienceConfig resilience = ResilienceConfig.defaults();
        private MemoryStore memoryStore;
        private MemoryConfig memoryConfig = MemoryConfig.defaults();
        private MemoryConsolidator memoryConsolidator;
        private RagStore ragStore;
        private int ragTopK = 4;
        private boolean gateEnabled;
        private String gateModel;
        private SkillManager skillManager;

        public Builder registerProvider(com.agent.model.Provider provider) {
            registry.registerProvider(provider);
            return this;
        }

        public Builder registerModel(com.agent.model.ModelConfig model) {
            registry.registerModel(model);
            return this;
        }

        public Builder defaultModel(String providerModel) {
            this.defaultModel = providerModel;
            return this;
        }

        public Builder addInterceptor(Interceptor interceptor) {
            interceptors.add(interceptor);
            return this;
        }

        /** 注入外部装配好的注册表（Spring 自动装配路径），替换内部默认注册表。 */
        public Builder modelRegistry(ModelRegistry registry) {
            // 把外部注册表内容并入内部注册表（providers + models）
            registry.providers().all().forEach(this.registry::registerProvider);
            registry.all().forEach(this.registry::registerModel);
            return this;
        }

        public Builder transport(NettyHttpConfig config) {
            this.transport = config;
            return this;
        }

        public Builder resilience(ResilienceConfig config) {
            this.resilience = config;
            return this;
        }

        public Builder memoryStore(MemoryStore store) {
            this.memoryStore = store;
            return this;
        }

        public Builder memoryConfig(MemoryConfig config) {
            this.memoryConfig = config;
            return this;
        }

        public Builder memoryConsolidator(MemoryConsolidator consolidator) {
            this.memoryConsolidator = consolidator;
            return this;
        }

        public Builder ragStore(RagStore store) {
            this.ragStore = store;
            return this;
        }

        public Builder ragTopK(int topK) {
            this.ragTopK = topK;
            return this;
        }

        public Builder gate(boolean enabled, String model) {
            this.gateEnabled = enabled;
            this.gateModel = model;
            return this;
        }

        public Builder skillManager(SkillManager manager) {
            this.skillManager = manager;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}

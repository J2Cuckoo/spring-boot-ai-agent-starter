package com.agent.config;

import com.agent.Agent;
import com.agent.contract.Tool;
import com.agent.embedding.EmbeddingConfig;
import com.agent.embedding.EmbeddingModel;
import com.agent.embedding.OllamaEmbeddingModel;
import com.agent.embedding.OpenAIEmbeddingModel;
import com.agent.memory.DefaultMemoryStore;
import com.agent.memory.MemoryConfig;
import com.agent.memory.MemoryConsolidator;
import com.agent.memory.MemoryStore;
import com.agent.model.ModelConfig;
import com.agent.model.ModelRegistry;
import com.agent.model.Provider;
import com.agent.model.ProviderRegistry;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.impl.HumanizeInterceptor;
import com.agent.pipeline.impl.LoggingInterceptor;
import com.agent.pipeline.impl.RateLimitInterceptor;
import com.agent.pipeline.impl.RetryInterceptor;
import com.agent.pipeline.impl.SkillInjectInterceptor;
import com.agent.pipeline.impl.TimeoutInterceptor;
import com.agent.rag.RagStore;
import com.agent.resilience.ResilienceConfig;
import com.agent.resilience.ResilienceManager;
import com.agent.skill.DefaultSkillManager;
import com.agent.skill.SkillManager;
import com.agent.transport.NettyHttpConfig;
import com.agent.transport.NettyHttpClient;
import com.agent.util.AgentHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * 自动装配：把宿主 {@code spring.agent.*} 配置装配为运行时 Bean。
 * 所有 Bean 带 {@code @ConditionalOnMissingBean}，宿主可覆盖；
 * MemoryStore/RagStore/Interceptor 支持宿主 @Bean 或 ServiceLoader 注册。
 * 依赖宿主 Redis（可选）与 Milvus SDK（可选）：缺失时对应能力自动降级关闭并告警。
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProperties.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    // ------------------------------------------------------------ 传输与容错

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public NettyHttpClient nettyHttpClient(AgentProperties props) {
        AgentProperties.Transport t = props.getTransport();
        return new NettyHttpClient(NettyHttpConfig.builder()
                .workerThreads(t.getWorkerThreads())
                .maxConnectionsPerEndpoint(t.getMaxConnectionsPerEndpoint())
                .maxPendingAcquires(t.getMaxPendingAcquires())
                .acquireTimeoutMs(t.getAcquireTimeoutMs())
                .connectTimeoutMs(t.getConnectTimeoutMs())
                .requestTimeoutMs(t.getRequestTimeoutMs())
                .idleTimeoutSec(t.getIdleTimeoutSec())
                .build());
    }

    @Bean
    @ConditionalOnMissingBean
    public ResilienceManager resilienceManager(AgentProperties props) {
        AgentProperties.Resilience r = props.getResilience();
        return new ResilienceManager(ResilienceConfig.builder()
                .enabled(r.isEnabled())
                .retryMaxAttempts(r.getRetryMaxAttempts())
                .retryBackoff(r.getRetryBackoff())
                .requestTimeout(r.getRequestTimeout())
                .heartbeatMaxIdle(r.getHeartbeatMaxIdle())
                .breakerEnabled(r.isBreakerEnabled())
                .breakerWindowSize(r.getBreakerWindowSize())
                .breakerFailureRateThreshold(r.getBreakerFailureRateThreshold())
                .breakerWaitDuration(r.getBreakerWaitDuration())
                .rateLimitPermitsPerSecond(r.getRateLimitPermitsPerSecond())
                .rateLimitBurst(r.getRateLimitBurst())
                .build());
    }

    // ------------------------------------------------------------ 注册表

    @Bean
    @ConditionalOnMissingBean
    public ProviderRegistry providerRegistry(AgentProperties props) {
        ProviderRegistry registry = new ProviderRegistry();
        props.getProviders().forEach((name, p) -> {
            Provider.Builder builder = Provider.builder(name, normalizeProviderType(p.getType(), name))
                    .baseUrl(p.getBaseUrl())
                    .apiKey(p.getApiKey());
            if (p.getTimeout() != null) {
                builder.timeoutMs(p.getTimeout().toMillis());
            }
            builder.maxRetries(p.getMaxRetries());
            if (p.getHeaders() != null && !p.getHeaders().isEmpty()) {
                builder.headers(p.getHeaders());
            }
            registry.register(builder.build());
        });
        return registry;
    }

    /**
     * 归一化提供商类型：大小写不敏感匹配枚举；缺省或未识别的值
     * （如 deepseek、moonshot 等 OpenAI 兼容厂商名）按 CUSTOM（OpenAI 兼容协议）处理并告警。
     */
    static com.agent.model.ProviderType normalizeProviderType(String raw, String providerName) {
        if (raw == null || raw.isBlank()) {
            return com.agent.model.ProviderType.CUSTOM;
        }
        try {
            return com.agent.model.ProviderType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("provider[{}].type={} 未识别，按 CUSTOM（OpenAI 兼容协议）处理；"
                    + "可选值：OPENAI/CLAUDE/GEMINI/OLLAMA/CUSTOM", providerName, raw);
            return com.agent.model.ProviderType.CUSTOM;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry modelRegistry(AgentProperties props, ProviderRegistry providers) {
        ModelRegistry registry = new ModelRegistry();
        providers.all().forEach(registry::registerProvider);
        props.getModels().forEach((alias, m) -> registry.registerModel(ModelConfig.builder(
                        alias, m.getProvider(), m.getModelName())
                .temperature(m.getTemperature())
                .maxTokens(m.getMaxTokens())
                .stream(m.getStream())
                .extra(m.getExtra())
                .memoryDatabase(m.getMemory().getDatabase())
                .build()));
        return registry;
    }

    // ------------------------------------------------------------ 技能

    @Bean
    @ConditionalOnMissingBean
    public SkillManager skillManager() {
        return new DefaultSkillManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillInjectInterceptor skillInjectInterceptor(AgentProperties props, SkillManager skillManager) {
        List<String> docs = new ArrayList<>();
        // 1) 宿主配置的路径（文件或目录，目录下扫描 *.skill.md）
        docs.addAll(loadHostSkillPaths(props.getSkills().getPaths()));
        // 2) 宿主 classpath 的 skills/*.skill.md（SDK 不内置任何 skill.md）
        docs.addAll(loadClasspathSkillDocs());
        // 3) 已注册技能（含内置 calculator/search 与宿主 SPI 注册）的清单
        if (props.getSkills().isIncludeRegistry()) {
            String registry = skillManager.all().stream()
                    .map(s -> "## " + s.name() + "（" + s.id() + "）\n" + s.description())
                    .collect(Collectors.joining("\n\n"));
            if (!registry.isBlank()) {
                docs.add(registry);
            }
        }
        return new SkillInjectInterceptor(docs);
    }

    /** 宿主 skill.md 路径加载：文件直读，目录扫描 *.skill.md；缺失路径跳过并告警。 */
    private List<String> loadHostSkillPaths(List<String> paths) {
        List<String> docs = new ArrayList<>();
        if (paths == null) {
            return docs;
        }
        for (String p : paths) {
            if (p == null || p.isBlank()) {
                continue;
            }
            try {
                java.nio.file.Path path = java.nio.file.Path.of(p);
                if (!java.nio.file.Files.exists(path)) {
                    log.warn("skill.md 路径不存在（跳过）: {}", p);
                    continue;
                }
                if (java.nio.file.Files.isDirectory(path)) {
                    try (var stream = java.nio.file.Files.list(path)) {
                        stream.filter(f -> f.toString().endsWith(".skill.md"))
                                .sorted()
                                .forEach(f -> readFileOrNull(f).ifPresent(docs::add));
                    }
                } else {
                    readFileOrNull(path).ifPresent(docs::add);
                }
            } catch (Exception e) {
                log.warn("skill.md 加载失败（跳过）: {} -> {}", p, e.getMessage());
            }
        }
        return docs;
    }

    private java.util.Optional<String> readFileOrNull(java.nio.file.Path file) {
        try {
            return java.util.Optional.of(java.nio.file.Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("skill.md 读取失败（跳过）: {} -> {}", file, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private List<String> loadClasspathSkillDocs() {
        List<String> docs = new ArrayList<>();
        try {
            var resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:skills/*.skill.md");
            for (var res : resources) {
                try (var in = res.getInputStream()) {
                    docs.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            log.debug("classpath skill.md 加载失败（跳过）: {}", e.getMessage());
        }
        return docs;
    }

    // ------------------------------------------------------------ 向量化（RAG 前置）

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(prefix = "spring.agent.embedding", name = "model")
    public EmbeddingModel embeddingModel(AgentProperties props, NettyHttpClient http,
                                         ModelRegistry registry) {
        String providerName = props.getEmbedding().getProvider();
        Provider provider = registry.providers().require(providerName);
        EmbeddingConfig cfg = new EmbeddingConfig(providerName,
                props.getEmbedding().getModel(), props.getEmbedding().getDimensions());
        return "openai".equalsIgnoreCase(providerName)
                ? new OpenAIEmbeddingModel(http, provider, cfg)
                : new OllamaEmbeddingModel(http, provider, cfg);
    }

    // ------------------------------------------------------------ 门禁

    @Bean
    @ConditionalOnProperty(prefix = "spring.agent.gate", name = "human-gate", havingValue = "true")
    @ConditionalOnMissingBean
    public HumanizeInterceptor humanizeInterceptor(AgentProperties props) {
        return new HumanizeInterceptor(props.getGate().isHumanGate(), props.getGate().getModel());
    }

    // ------------------------------------------------------------ 通用拦截器

    @Bean
    @ConditionalOnMissingBean
    public LoggingInterceptor loggingInterceptor() {
        return new LoggingInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitInterceptor rateLimitInterceptor(ResilienceManager resilience) {
        return new RateLimitInterceptor(resilience);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryInterceptor retryInterceptor(ResilienceManager resilience) {
        return new RetryInterceptor(resilience);
    }

    @Bean
    @ConditionalOnMissingBean
    public TimeoutInterceptor timeoutInterceptor(ResilienceManager resilience) {
        return new TimeoutInterceptor(resilience);
    }

    // ------------------------------------------------------------ 门面

    @Bean
    @ConditionalOnMissingBean
    public AgentHolder agentHolder() {
        return new AgentHolder();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(Agent.class)
    public Agent agent(AgentProperties props, ModelRegistry modelRegistry, ResilienceManager resilience,
                       SkillManager skillManager, ObjectProvider<MemoryStore> memoryStores,
                       ObjectProvider<MemoryConsolidator> consolidators, ObjectProvider<RagStore> ragStores,
                       ObjectProvider<SkillInjectInterceptor> skillInject,
                       ObjectProvider<HumanizeInterceptor> humanize,
                       ObjectProvider<RateLimitInterceptor> rateLimit,
                       ObjectProvider<RetryInterceptor> retry,
                       ObjectProvider<TimeoutInterceptor> timeout,
                       ObjectProvider<LoggingInterceptor> logging,
                       ObjectProvider<Interceptor> customInterceptors,
                       ObjectProvider<Tool> toolBeans,
                       AgentHolder holder) {
        Agent.Builder builder = Agent.builder()
                .defaultModel(props.getDefaultModel())
                .modelRegistry(modelRegistry)
                .gate(props.getGate().isHumanGate(), props.getGate().getModel())
                .skillManager(skillManager);

        // 记忆：enabled=false 完全关闭；默认开启（优先自定义 Bean，其次 Redis 默认实现）
        if (props.getMemory().isEnabled()) {
            MemoryStore memory = resolveStore(memoryStores, MemoryStore.class);
            if (memory == null) {
                log.warn("记忆已开启但未检测到 Redis（StringRedisTemplate）且无自定义 MemoryStore，记忆能力降级关闭");
            } else {
                builder.memoryStore(memory);
            }
        } else {
            log.info("记忆功能已关闭（spring.agent.memory.enabled=false）");
        }
        // RAG：默认关闭；显式 rag.enabled=true 才启用（优先自定义 Bean，其次 Milvus 默认实现）
        if (props.getRag().isEnabled()) {
            RagStore rag = resolveStore(ragStores, RagStore.class);
            if (rag != null) {
                builder.ragStore(rag).ragTopK(props.getRag().getTopK());
            }
        }
        MemoryConsolidator consolidator = consolidators.getIfAvailable();
        if (consolidator != null) {
            builder.memoryConsolidator(consolidator);
        }

        List<Interceptor> interceptors = new ArrayList<>();
        logging.ifAvailable(interceptors::add);
        rateLimit.ifAvailable(interceptors::add);
        retry.ifAvailable(interceptors::add);
        timeout.ifAvailable(interceptors::add);
        skillInject.ifAvailable(interceptors::add);
        customInterceptors.orderedStream().forEach(interceptors::add);
        humanize.ifAvailable(interceptors::add);
        interceptors.forEach(builder::addInterceptor);

        // 声明式工具：自动收集所有 Tool Bean，无需 @PostConstruct 手动注册
        toolBeans.orderedStream().forEach(builder::tool);

        Agent agent = builder.build();
        holder.set(agent);
        return agent;
    }

    /** Bean 优先，其次 ServiceLoader 声明的实现。 */
    private <T> T resolveStore(ObjectProvider<T> provider, Class<T> type) {
        T fromBean = provider.getIfAvailable();
        if (fromBean != null) {
            return fromBean;
        }
        return ServiceLoader.load(type, type.getClassLoader()).findFirst().orElse(null);
    }

    // ------------------------------------------------------------ 可选依赖装配（嵌套类，类缺失时不加载）

    /**
     * 记忆装配：默认开启（memory.enabled=true）；宿主提供 StringRedisTemplate 时装配 Redis 三层记忆，
     * 设 memory.enabled=false 可显式关闭（即使已引入 Redis）。
     */
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    static class MemoryConfiguration {

        @Bean
        @ConditionalOnProperty(prefix = "spring.agent.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
        @ConditionalOnBean(org.springframework.data.redis.core.StringRedisTemplate.class)
        @ConditionalOnMissingBean(MemoryStore.class)
        public DefaultMemoryStore memoryStore(AgentProperties props,
                                              org.springframework.data.redis.core.StringRedisTemplate template) {
            return new DefaultMemoryStore(template, toMemoryConfig(props));
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnBean(DefaultMemoryStore.class)
        @ConditionalOnMissingBean
        public MemoryConsolidator memoryConsolidator(DefaultMemoryStore store, AgentProperties props,
                                                     AgentHolder holder) {
            // 整合浓缩交给默认模型（经 AgentHolder 延迟解引用，装配期打破循环）
            return new MemoryConsolidator(store, toMemoryConfig(props),
                    transcripts -> holder.get().write(
                            "把以下对话记忆整合浓缩为长期记忆要点，逐条列出，只输出要点：\n\n"
                                    + String.join("\n", transcripts)));
        }

        @Bean
        @ConditionalOnBean(MemoryStore.class)
        @ConditionalOnMissingBean
        public com.agent.pipeline.impl.MemoryInterceptor memoryInterceptor(
                MemoryStore store, ObjectProvider<MemoryConsolidator> consolidator) {
            return new com.agent.pipeline.impl.MemoryInterceptor(store, consolidator.getIfAvailable());
        }
    }

    /**
     * RAG 装配：默认关闭（rag.enabled=false）；显式设 rag.enabled=true 且引入 milvus-sdk、
     * 配置了 embedding 模型时启用 Milvus。
     */
    @ConditionalOnClass(name = "io.milvus.client.MilvusServiceClient")
    static class RagConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnProperty(prefix = "spring.agent.rag", name = "enabled", havingValue = "true")
        @ConditionalOnBean(EmbeddingModel.class)
        @ConditionalOnMissingBean(RagStore.class)
        public RagStore milvusRagStore(AgentProperties props, EmbeddingModel embedding) {
            AgentProperties.Rag.Milvus m = props.getRag().getMilvus();
            return new com.agent.rag.MilvusRagStore(new com.agent.rag.RagConfig(
                    props.getRag().getType(), m.getHost(), m.getPort(), m.getCollection(),
                    props.getRag().getTopK()), embedding);
        }
    }

    static MemoryConfig toMemoryConfig(AgentProperties props) {
        AgentProperties.Memory m = props.getMemory();
        return MemoryConfig.builder()
                .database(m.getDatabase())
                .reverseOptimize(m.isReverseOptimize())
                .consolidationMinRounds(m.getConsolidation().getMinRounds())
                .cacheTtl(m.getCache().getTtl())
                .cacheMaxSizeBytes(m.getCache().getMaxSize().toBytes())
                .temporaryTtl(m.getTemporary().getTtl())
                .shortTermTtl(m.getShortTerm().getTtl())
                .build();
    }
}

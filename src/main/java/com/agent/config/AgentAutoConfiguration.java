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
import java.util.Map;
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
    public ProviderRegistry providerRegistry(AgentProperties props,
                                             org.springframework.core.env.Environment env) {
        ProviderRegistry registry = new ProviderRegistry();
        // 列表结构：每个元素是"provider 名 -> 配置"的单键映射（手动还原，binder 不支持混合列表）
        for (Map.Entry<String, Map<String, Object>> entry :
                ProviderConfigReader.readProviders(env).entrySet()) {
            String name = entry.getKey();
            Map<String, Object> cfg = entry.getValue();
            {
                String type = asString(cfg.get("type"));
                Provider.Builder builder = Provider.builder(name, normalizeProviderType(type, name))
                        .baseUrl(asString(cfg.get("base-url")));
                String apiKey = asString(cfg.get("api-key"));
                if (apiKey != null) {
                    builder.apiKey(apiKey);
                }
                Object timeout = cfg.get("timeout");
                if (timeout != null && !timeout.toString().isBlank()) {
                    try {
                        builder.timeoutMs(java.time.Duration.parse(
                                timeout.toString().startsWith("PT") ? timeout.toString()
                                        : "PT" + normalizeDuration(timeout.toString())).toMillis());
                    } catch (Exception ignored) {
                        builder.timeoutMs(Long.parseLong(timeout.toString()));
                    }
                }
                Integer maxRetries = asInt(cfg.get("max-retries"));
                if (maxRetries != null) {
                    builder.maxRetries(maxRetries);
                }
                if (cfg.get("headers") instanceof Map<?, ?> headers && !headers.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> headerMap = (Map<String, String>) headers;
                    builder.headers(headerMap);
                }
                registry.register(builder.build());
            }
        }
        return registry;
    }

    /**
     * 归一化协议格式。支持的写法（大小写不敏感，中划线/下划线等价）：
     * chat-completions / openai-chat-completions / openai（旧） / custom（旧）→ Chat Completions；
     * responses / openai-responses → Responses API；
     * anthropic-messages / claude（旧）→ Anthropic Messages；
     * gemini、ollama。缺省或未识别值（厂商名如 deepseek）按 Chat Completions 处理并告警。
     */
    static com.agent.model.ProviderType normalizeProviderType(String raw, String providerName) {
        if (raw == null || raw.isBlank()) {
            return com.agent.model.ProviderType.OPENAI_CHAT_COMPLETIONS;
        }
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-').replace(' ', '-');
        return switch (key) {
            case "chat-completions", "openai-chat-completions", "openai", "custom", "openai-chat" ->
                    com.agent.model.ProviderType.OPENAI_CHAT_COMPLETIONS;
            case "responses", "openai-responses" -> com.agent.model.ProviderType.OPENAI_RESPONSES;
            case "anthropic-messages", "claude", "anthropic", "messages" ->
                    com.agent.model.ProviderType.ANTHROPIC_MESSAGES;
            case "gemini" -> com.agent.model.ProviderType.GEMINI;
            case "ollama" -> com.agent.model.ProviderType.OLLAMA;
            default -> {
                log.warn("provider[{}].type={} 未识别，按 OpenAI Chat Completions 兼容格式处理；"
                        + "可选值：chat-completions / responses / anthropic-messages / gemini / ollama",
                        providerName, raw);
                yield com.agent.model.ProviderType.OPENAI_CHAT_COMPLETIONS;
            }
        };
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    /** Spring Duration 简写（10s、3d）转 ISO-8601 后缀。 */
    private static String normalizeDuration(String raw) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d+)(ms|s|m|h|d)").matcher(raw.trim());
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            String unit = switch (m.group(2)) {
                case "ms" -> "0." + String.format("%03d", n) + "S";
                case "s" -> n + "S";
                case "m" -> n + "M";
                case "h" -> n + "H";
                case "d" -> n * 24 + "H";
                default -> n + "S";
            };
            sb.append(unit);
        }
        return sb.length() > 0 ? sb.toString() : raw;
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry modelRegistry(AgentProperties props, ProviderRegistry providers,
                                       org.springframework.core.env.Environment env) {
        ModelRegistry registry = new ModelRegistry();
        providers.all().forEach(registry::registerProvider);

        // provider 内嵌 models：字符串（别名=真实名）或对象（完整配置），继承 provider 级默认参数
        for (Map.Entry<String, Map<String, Object>> entry :
                ProviderConfigReader.readProviders(env).entrySet()) {
            String providerName = entry.getKey();
            {
                Map<String, Object> cfg = entry.getValue();
                Double providerTemperature = asDouble(cfg.get("temperature"));
                Integer providerMaxTokens = asInt(cfg.get("max-tokens"));
                Object models = cfg.get("models");
                if (!(models instanceof List<?> modelList)) {
                    continue;
                }
                for (Object item : modelList) {
                    if (item instanceof String modelName) {
                        // 字符串形式：别名等于厂商真实模型名
                        registry.registerModel(ModelConfig.builder(modelName, providerName, modelName)
                                .temperature(providerTemperature)
                                .maxTokens(providerMaxTokens)
                                .build());
                    } else if (item instanceof Map<?, ?> modelCfgRaw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> modelCfg = (Map<String, Object>) modelCfgRaw;
                        String alias = asString(modelCfg.get("name"));
                        String modelName = asString(modelCfg.get("model-name"));
                        if (alias == null || modelName == null) {
                            log.warn("provider[{}] 的模型配置缺少 name 或 model-name，跳过：{}", providerName, modelCfg);
                            continue;
                        }
                        Integer memoryDb = modelCfg.get("memory") instanceof Map<?, ?> mem
                                ? asInt(((Map<?, ?>) mem).get("database")) : null;
                        Map<String, Object> extra = modelCfg.get("extra") instanceof Map<?, ?> ex
                                ? new java.util.LinkedHashMap<>((Map<String, Object>) ex) : Map.of();
                        Boolean stream = modelCfg.get("stream") instanceof Boolean b ? b : Boolean.TRUE;
                        registry.registerModel(ModelConfig.builder(alias, providerName, modelName)
                                .temperature(asDouble(modelCfg.get("temperature")) != null
                                        ? asDouble(modelCfg.get("temperature")) : providerTemperature)
                                .maxTokens(asInt(modelCfg.get("max-tokens")) != null
                                        ? asInt(modelCfg.get("max-tokens")) : providerMaxTokens)
                                .stream(stream)
                                .extra(extra)
                                .memoryDatabase(memoryDb)
                                .build());
                    }
                }
            }
        }

        // 顶层 models（旧结构，与内嵌 models 并存）
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

    private static Double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String str && !str.isBlank()) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
                                              org.springframework.data.redis.core.StringRedisTemplate template,
                                              ObjectProvider<org.springframework.data.redis.connection.RedisConnectionFactory> factories) {
            org.springframework.data.redis.connection.RedisConnectionFactory factory =
                    factories.getIfAvailable();
            if (factory != null) {
                // 连接池由宿主 spring.data.redis.* 配置（lettuce.pool 或 jedis.pool），
                // 此处确认实际使用的池实现，方便排查"池没生效"的问题
                log.info("记忆复用宿主 Redis 连接工厂: {}（池参数在 spring.data.redis.lettuce.pool 或 jedis.pool 配置）",
                        factory.getClass().getSimpleName());
            }
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
                    props.getRag().getTopK(), m.getPool().getMaxSize(),
                    m.getPool().getAcquireTimeoutMs()), embedding);
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

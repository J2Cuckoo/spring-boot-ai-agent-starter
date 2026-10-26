package com.agent.config;

import com.agent.model.ProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宿主配置绑定：{@code spring.agent.*}。SDK 不内置任何固定 Key 或端点。
 */
@ConfigurationProperties(prefix = "spring.agent")
public class AgentProperties {

    /** 默认模型引用 provider:model。 */
    private String defaultModel;

    private final Gate gate = new Gate();
    private final Skills skills = new Skills();
    private final Memory memory = new Memory();
    private final Embedding embedding = new Embedding();
    private final Rag rag = new Rag();
    private final Transport transport = new Transport();
    private final Resilience resilience = new Resilience();

    private Map<String, ProviderProps> providers = new LinkedHashMap<>();
    private Map<String, ModelProps> models = new LinkedHashMap<>();

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Gate getGate() {
        return gate;
    }

    public Skills getSkills() {
        return skills;
    }

    public Memory getMemory() {
        return memory;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Rag getRag() {
        return rag;
    }

    public Transport getTransport() {
        return transport;
    }

    public Resilience getResilience() {
        return resilience;
    }

    public Map<String, ProviderProps> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderProps> providers) {
        this.providers = providers;
    }

    public Map<String, ModelProps> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelProps> models) {
        this.models = models;
    }

    // ---------------------------------------------------------------- gate

    public static class Gate {
        /** 真人门禁（去 AI 味），默认关闭。 */
        private boolean humanGate = false;
        /** 门禁模型 provider:model，human-gate 开启时必填。 */
        private String model;

        public boolean isHumanGate() {
            return humanGate;
        }

        public void setHumanGate(boolean humanGate) {
            this.humanGate = humanGate;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    // ---------------------------------------------------------------- skills

    /**
     * 宿主技能配置：skill.md 由宿主应用提供，SDK 不内置。
     */
    public static class Skills {
        /**
         * 宿主 skill.md 路径列表，元素可为文件或目录（目录下扫描 *.skill.md）。
         */
        private java.util.List<String> paths = new java.util.ArrayList<>();
        /** 是否把已注册技能（含内置 calculator/search 与宿主 SPI 注册）的清单注入上下文。 */
        private boolean includeRegistry = true;

        public java.util.List<String> getPaths() {
            return paths;
        }

        public void setPaths(java.util.List<String> paths) {
            this.paths = paths;
        }

        public boolean isIncludeRegistry() {
            return includeRegistry;
        }

        public void setIncludeRegistry(boolean includeRegistry) {
            this.includeRegistry = includeRegistry;
        }
    }

    // ---------------------------------------------------------------- memory

    public static class Memory {
        /** 记忆功能总开关，默认开启；设为 false 时不装配记忆存储与记忆拦截器（即使宿主已引入 Redis）。 */
        private boolean enabled = true;
        /** 记忆隔离库（宿主默认库与 agent 库分离）。 */
        private int database = 15;
        /** 记忆反向优化，默认关闭。 */
        private boolean reverseOptimize = false;
        private final Consolidation consolidation = new Consolidation();
        private final Cache cache = new Cache();
        private final Temporary temporary = new Temporary();
        private final ShortTerm shortTerm = new ShortTerm();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public boolean isReverseOptimize() {
            return reverseOptimize;
        }

        public void setReverseOptimize(boolean reverseOptimize) {
            this.reverseOptimize = reverseOptimize;
        }

        public Consolidation getConsolidation() {
            return consolidation;
        }

        public Cache getCache() {
            return cache;
        }

        public Temporary getTemporary() {
            return temporary;
        }

        public ShortTerm getShortTerm() {
            return shortTerm;
        }

        public static class Consolidation {
            /** 达到该轮次触发后台整合浓缩。 */
            private int minRounds = 100;

            public int getMinRounds() {
                return minRounds;
            }

            public void setMinRounds(int minRounds) {
                this.minRounds = minRounds;
            }
        }

        public static class Cache {
            /** 热记忆内存缓存时效。 */
            private Duration ttl = Duration.ofMinutes(30);
            /** 内存缓存上限，超限写回 Redis。 */
            private DataSize maxSize = DataSize.ofMegabytes(20);

            public Duration getTtl() {
                return ttl;
            }

            public void setTtl(Duration ttl) {
                this.ttl = ttl;
            }

            public DataSize getMaxSize() {
                return maxSize;
            }

            public void setMaxSize(DataSize maxSize) {
                this.maxSize = maxSize;
            }
        }

        public static class Temporary {
            /** 临时记忆过期时间。 */
            private Duration ttl = Duration.ofDays(3);

            public Duration getTtl() {
                return ttl;
            }

            public void setTtl(Duration ttl) {
                this.ttl = ttl;
            }
        }

        public static class ShortTerm {
            /** 短期记忆过期时间。 */
            private Duration ttl = Duration.ofDays(7);

            public Duration getTtl() {
                return ttl;
            }

            public void setTtl(Duration ttl) {
                this.ttl = ttl;
            }
        }
    }

    // ---------------------------------------------------------------- embedding

    public static class Embedding {
        /** openai 或 ollama。 */
        private String provider = "ollama";
        private String model;
        private int dimensions = 768;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }
    }

    // ---------------------------------------------------------------- rag

    public static class Rag {
        /** RAG 功能总开关，默认关闭；设为 true 才装配 RagStore 与 RAG 拦截器。 */
        private boolean enabled = false;
        /** 向量库类型，默认 milvus。 */
        private String type = "milvus";
        private int topK = 4;
        private final Milvus milvus = new Milvus();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public Milvus getMilvus() {
            return milvus;
        }

        public static class Milvus {
            private String host = "localhost";
            private int port = 19530;
            private String collection = "agent-memory";

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public String getCollection() {
                return collection;
            }

            public void setCollection(String collection) {
                this.collection = collection;
            }
        }
    }

    // ---------------------------------------------------------------- transport

    public static class Transport {
        private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        private int maxConnectionsPerEndpoint = Runtime.getRuntime().availableProcessors() * 4;
        private int maxPendingAcquires = 65536;
        private int acquireTimeoutMs = 10_000;
        private int connectTimeoutMs = 10_000;
        private int requestTimeoutMs = 120_000;
        private int idleTimeoutSec = 60;

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public int getMaxConnectionsPerEndpoint() {
            return maxConnectionsPerEndpoint;
        }

        public void setMaxConnectionsPerEndpoint(int v) {
            this.maxConnectionsPerEndpoint = v;
        }

        public int getMaxPendingAcquires() {
            return maxPendingAcquires;
        }

        public void setMaxPendingAcquires(int v) {
            this.maxPendingAcquires = v;
        }

        public int getAcquireTimeoutMs() {
            return acquireTimeoutMs;
        }

        public void setAcquireTimeoutMs(int v) {
            this.acquireTimeoutMs = v;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int v) {
            this.connectTimeoutMs = v;
        }

        public int getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(int v) {
            this.requestTimeoutMs = v;
        }

        public int getIdleTimeoutSec() {
            return idleTimeoutSec;
        }

        public void setIdleTimeoutSec(int v) {
            this.idleTimeoutSec = v;
        }
    }

    // ---------------------------------------------------------------- resilience

    public static class Resilience {
        private boolean enabled = true;
        private int retryMaxAttempts = 3;
        private Duration retryBackoff = Duration.ofMillis(100);
        private Duration requestTimeout = Duration.ofSeconds(120);
        private Duration heartbeatMaxIdle = Duration.ofSeconds(30);
        private boolean breakerEnabled = true;
        private int breakerWindowSize = 20;
        private double breakerFailureRateThreshold = 50;
        private Duration breakerWaitDuration = Duration.ofSeconds(10);
        /** 每秒令牌数，0 = 关闭限流。 */
        private double rateLimitPermitsPerSecond = 0;
        private double rateLimitBurst = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRetryMaxAttempts() {
            return retryMaxAttempts;
        }

        public void setRetryMaxAttempts(int v) {
            this.retryMaxAttempts = v;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration v) {
            this.retryBackoff = v;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration v) {
            this.requestTimeout = v;
        }

        public Duration getHeartbeatMaxIdle() {
            return heartbeatMaxIdle;
        }

        public void setHeartbeatMaxIdle(Duration v) {
            this.heartbeatMaxIdle = v;
        }

        public boolean isBreakerEnabled() {
            return breakerEnabled;
        }

        public void setBreakerEnabled(boolean v) {
            this.breakerEnabled = v;
        }

        public int getBreakerWindowSize() {
            return breakerWindowSize;
        }

        public void setBreakerWindowSize(int v) {
            this.breakerWindowSize = v;
        }

        public double getBreakerFailureRateThreshold() {
            return breakerFailureRateThreshold;
        }

        public void setBreakerFailureRateThreshold(double v) {
            this.breakerFailureRateThreshold = v;
        }

        public Duration getBreakerWaitDuration() {
            return breakerWaitDuration;
        }

        public void setBreakerWaitDuration(Duration v) {
            this.breakerWaitDuration = v;
        }

        public double getRateLimitPermitsPerSecond() {
            return rateLimitPermitsPerSecond;
        }

        public void setRateLimitPermitsPerSecond(double v) {
            this.rateLimitPermitsPerSecond = v;
        }

        public double getRateLimitBurst() {
            return rateLimitBurst;
        }

        public void setRateLimitBurst(double v) {
            this.rateLimitBurst = v;
        }
    }

    // ---------------------------------------------------------------- providers / models

    public static class ProviderProps {
        private ProviderType type;
        private String baseUrl;
        private String apiKey;
        private Duration timeout;
        private int maxRetries;
        private Map<String, String> headers = new LinkedHashMap<>();

        public ProviderType getType() {
            return type;
        }

        public void setType(ProviderType type) {
            this.type = type;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }

    public static class ModelProps {
        private String provider;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        private Boolean stream = Boolean.TRUE;
        private Map<String, Object> extra = new LinkedHashMap<>();
        private final MemoryOverride memory = new MemoryOverride();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Boolean getStream() {
            return stream;
        }

        public void setStream(Boolean stream) {
            this.stream = stream;
        }

        public Map<String, Object> getExtra() {
            return extra;
        }

        public void setExtra(Map<String, Object> extra) {
            this.extra = extra;
        }

        public MemoryOverride getMemory() {
            return memory;
        }

        public static class MemoryOverride {
            /** 模型级记忆库覆盖，高于全局。 */
            private Integer database;

            public Integer getDatabase() {
                return database;
            }

            public void setDatabase(Integer database) {
                this.database = database;
            }
        }
    }
}

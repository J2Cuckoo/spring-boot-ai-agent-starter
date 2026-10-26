package com.agent.memory;

import com.agent.serialize.JacksonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 默认记忆实现：临时/短期与长期索引存 Redis（复用宿主连接，database 隔离），
 * 长期正文存宿主根目录 {@code .memory/} 文件；高频临时记忆晋升 JVM 热缓存。
 */
public final class DefaultMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryStore.class);

    private final RedisFacade redis;
    private final LongTermMemoryRepository longTerm;
    private final HotMemoryCache hot;
    private final MemoryConfig config;
    private final MemoryReverseOptimizer reverseOptimizer;

    public DefaultMemoryStore(StringRedisTemplate template, MemoryConfig config) {
        this(new SpringDataRedisFacade(template), config, new LongTermMemoryRepository());
    }

    public DefaultMemoryStore(RedisFacade redis, MemoryConfig config, LongTermMemoryRepository longTerm) {
        this.redis = redis;
        this.config = config;
        this.longTerm = longTerm;
        this.hot = new HotMemoryCache(config.cacheTtl(), config.cacheMaxSizeBytes());
        // 超限/过期写回 Redis 临时层
        this.hot.setWriteBack((key, json) -> {
            try {
                redis.setex(key, json, config.temporaryTtl());
            } catch (Exception e) {
                log.debug("热记忆写回失败: {}", key, e);
            }
        });
        this.reverseOptimizer = new MemoryReverseOptimizer(config.reverseOptimize());
    }

    @Override
    public void save(String namespace, String key, Object data, MemoryTier tier) {
        String json;
        try {
            json = JacksonConfig.mapper().writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("记忆序列化失败", e);
        }
        String redisKey = redisKey(namespace, key, tier);
        switch (tier) {
            case TEMPORARY -> {
                String existing = redis.get(redisKey);
                if (!reverseOptimizer.shouldOverwrite(existing, json)) {
                    log.debug("记忆冲突且反向优化关闭，保留原记忆: {}", redisKey);
                    return;
                }
                redis.setex(redisKey, json, config.temporaryTtl());
                hot.evict(redisKey);   // 失效旧缓存，下次高频访问再晋升
            }
            case SHORT_TERM -> redis.setex(redisKey, json, config.shortTermTtl());
            case LONG_TERM -> {
                longTerm.write(namespace, key, json);
                // Redis 只存长期记忆索引与最简描述
                String desc = json.length() > 200 ? json.substring(0, 200) : json;
                redis.set(indexKey(namespace, key), desc);
            }
        }
    }

    @Override
    public <T> Optional<T> load(String namespace, String key, Class<T> type, MemoryTier tier) {
        String redisKey = redisKey(namespace, key, tier);
        Optional<String> json = switch (tier) {
            case TEMPORARY -> {
                // 查询顺序：内存热记忆 → Redis
                String cached = hot.get(redisKey);
                if (cached != null) {
                    yield Optional.of(cached);
                }
                String fromRedis = redis.get(redisKey);
                if (fromRedis != null) {
                    hot.recordAccess(redisKey, fromRedis);
                }
                yield Optional.ofNullable(fromRedis);
            }
            case SHORT_TERM -> Optional.ofNullable(redis.get(redisKey));
            case LONG_TERM -> {
                Optional<String> body = longTerm.read(namespace, key);
                if (body.isEmpty()) {
                    yield Optional.empty();
                }
                // 校验 Redis 索引仍存在（索引在则正文有效）
                yield body;
            }
        };
        return json.map(j -> {
            try {
                return JacksonConfig.mapper().readValue(j, type);
            } catch (Exception e) {
                log.debug("记忆反序列化失败 key={}", redisKey, e);
                return null;
            }
        });
    }

    @Override
    public void forget(String namespace, String key, MemoryTier tier) {
        String redisKey = redisKey(namespace, key, tier);
        hot.evict(redisKey);
        redis.del(redisKey);
        redis.del(indexKey(namespace, key));
        if (tier == MemoryTier.LONG_TERM) {
            longTerm.delete(namespace, key);
        }
    }

    // ------------------------------------------------ 轮次计数与整合枚举（内部）

    /** 对话轮次 +1，返回累计值。 */
    public long bumpRound(String namespace) {
        return redis.incr(roundKey(namespace));
    }

    public long rounds(String namespace) {
        String v = redis.get(roundKey(namespace));
        return v == null ? 0 : Long.parseLong(v);
    }

    public void resetRounds(String namespace) {
        redis.del(roundKey(namespace));
    }

    /** 枚举某命名空间下全部临时记忆（整合浓缩用，上限 64 条）。 */
    public List<String> listTemporary(String namespace) {
        String keyPrefix = prefix(namespace) + MemoryTier.TEMPORARY + ":";
        List<String> values = new ArrayList<>();
        for (String key : redis.keys(keyPrefix + "*")) {
            String v = redis.get(key);
            if (v != null) {
                values.add(key.substring(keyPrefix.length()) + "=" + v);
            }
            if (values.size() >= 64) {
                break;
            }
        }
        return values;
    }

    private String prefix(String namespace) {
        return "agent:mem:" + config.database() + ":" + namespace + ":";
    }

    private String redisKey(String namespace, String key, MemoryTier tier) {
        return prefix(namespace) + tier + ":" + key;
    }

    private String indexKey(String namespace, String key) {
        return prefix(namespace) + "lt-index:" + key;
    }

    private String roundKey(String namespace) {
        return prefix(namespace) + "rounds";
    }
}

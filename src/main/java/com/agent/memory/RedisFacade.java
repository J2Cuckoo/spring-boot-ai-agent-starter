package com.agent.memory;

import java.time.Duration;
import java.util.Set;

/**
 * Redis 最小操作门面（内部接口）：隔离 spring-data-redis 依赖，便于测试替换。
 */
interface RedisFacade {

    void setex(String key, String value, Duration ttl);

    void set(String key, String value);

    String get(String key);

    boolean del(String key);

    long incr(String key);

    Set<String> keys(String pattern);
}

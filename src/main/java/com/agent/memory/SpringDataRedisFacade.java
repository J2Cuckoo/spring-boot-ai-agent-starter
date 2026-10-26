package com.agent.memory;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

/**
 * 基于 StringRedisTemplate 的门面实现：复用宿主 Redis 连接。
 */
final class SpringDataRedisFacade implements RedisFacade {

    private final StringRedisTemplate template;

    SpringDataRedisFacade(StringRedisTemplate template) {
        this.template = template;
    }

    @Override
    public void setex(String key, String value, Duration ttl) {
        template.opsForValue().set(key, value, ttl);
    }

    @Override
    public void set(String key, String value) {
        template.opsForValue().set(key, value);
    }

    @Override
    public String get(String key) {
        return template.opsForValue().get(key);
    }

    @Override
    public boolean del(String key) {
        return Boolean.TRUE.equals(template.delete(key));
    }

    @Override
    public long incr(String key) {
        Long v = template.opsForValue().increment(key);
        return v == null ? 0 : v;
    }

    @Override
    public Set<String> keys(String pattern) {
        Set<String> keys = template.keys(pattern);
        return keys == null ? Set.of() : keys;
    }
}

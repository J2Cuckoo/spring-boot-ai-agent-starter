package com.agent.memory;

import java.util.Optional;

/**
 * 永久记忆 SPI（签名稳定，见 contract-specification.md §8.3）。
 * 实现并注册（@Bean 或 ServiceLoader）即被内置 MemoryInterceptor 使用。
 */
public interface MemoryStore {

    void save(String namespace, String key, Object data, MemoryTier tier);

    <T> Optional<T> load(String namespace, String key, Class<T> type, MemoryTier tier);

    void forget(String namespace, String key, MemoryTier tier);
}

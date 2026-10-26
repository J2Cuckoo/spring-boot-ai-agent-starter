package com.agent.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 热记忆内存缓存：TTL 30 分钟、LRU、容量按字符串估算字节数（×2 近似 UTF-16）。
 * 超限时先写回（回调）再逐出，腾出空间放入新热记忆。访问计数达到阈值才晋升入缓存。
 */
public final class HotMemoryCache {

    /** 达到该访问次数的临时记忆晋升为热记忆。 */
    static final int PROMOTE_THRESHOLD = 5;

    private static final class Entry {
        final String json;
        final long expireAt;
        int hits;

        Entry(String json, long ttlMs) {
            this.json = json;
            this.expireAt = System.currentTimeMillis() + ttlMs;
        }

        long bytes() {
            return json.length() * 2L;
        }
    }

    private final long ttlMs;
    private final long maxBytes;
    private final LinkedHashMap<String, Entry> lru = new LinkedHashMap<>(16, 0.75f, true);
    /** 访问计数走无锁 CHM：高频路径（每次 Redis 未命中）不抢 LRU 结构锁。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> accessCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private BiConsumer<String, String> writeBack = (k, v) -> {
    };

    public HotMemoryCache(java.time.Duration ttl, long maxBytes) {
        this.ttlMs = ttl.toMillis();
        this.maxBytes = Math.max(16, maxBytes);
    }

    public void setWriteBack(BiConsumer<String, String> writeBack) {
        this.writeBack = writeBack;
    }

    /** 命中返回 JSON，过期则逐出并返回 null。 */
    public synchronized String get(String key) {
        Entry e = lru.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() >= e.expireAt) {
            evict(key, e);
            return null;
        }
        e.hits++;
        return e.json;
    }

    /** 记录一次访问，达到阈值晋升入缓存（计数无锁，晋升本身需 LRU 结构锁）。 */
    public void recordAccess(String key, String loadedJson) {
        int n = accessCounts.merge(key, 1, Integer::sum);
        if (n < PROMOTE_THRESHOLD) {
            return;
        }
        if (accessCounts.remove(key) != null && !cached(key)) {
            put(key, loadedJson);
        }
    }

    private synchronized boolean cached(String key) {
        return lru.containsKey(key);
    }

    public synchronized void put(String key, String json) {
        if (json == null) {
            return;
        }
        long size = key.length() * 2L + json.length() * 2L;
        if (size > maxBytes) {
            return;
        }
        // 先逐出最久未使用直到放得下
        while (currentBytes() + size > maxBytes) {
            Map.Entry<String, Entry> eldest = lru.entrySet().iterator().next();
            evict(eldest.getKey(), eldest.getValue());
            if (lru.isEmpty()) {
                break;
            }
        }
        lru.put(key, new Entry(json, ttlMs));
    }

    public synchronized void evict(String key) {
        Entry e = lru.remove(key);
        if (e != null) {
            evict(key, e);
        }
    }

    private void evict(String key, Entry e) {
        lru.remove(key);
        // 超限/过期写回 Redis（Redis 本就是数据源，此处刷新 TTL 语义，保证不丢）
        writeBack.accept(key, e.json);
    }

    private long currentBytes() {
        long total = 0;
        for (Map.Entry<String, Entry> e : lru.entrySet()) {
            total += e.getKey().length() * 2L + e.getValue().bytes();
        }
        return total;
    }

    public synchronized int size() {
        return lru.size();
    }

    public synchronized long estimatedBytes() {
        return currentBytes();
    }
}

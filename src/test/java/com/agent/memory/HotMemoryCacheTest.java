package com.agent.memory;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HotMemoryCacheTest {

    @Test
    void putGetAndTtlExpiry() throws InterruptedException {
        List<String> writtenBack = new ArrayList<>();
        HotMemoryCache cache = new HotMemoryCache(Duration.ofMillis(100), 64 * 1024);
        cache.setWriteBack((k, v) -> writtenBack.add(k));
        cache.put("k1", "v1");
        assertThat(cache.get("k1")).isEqualTo("v1");
        Thread.sleep(150);
        assertThat(cache.get("k1")).isNull();          // 过期逐出
        assertThat(writtenBack).containsExactly("k1"); // 过期写回
        assertThat(cache.size()).isZero();
    }

    @Test
    void promotionAfterFrequentAccess() {
        HotMemoryCache cache = new HotMemoryCache(Duration.ofMinutes(30), 64 * 1024);
        for (int i = 0; i < HotMemoryCache.PROMOTE_THRESHOLD; i++) {
            cache.recordAccess("hot", "value");        // Redis 命中后记录访问
        }
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("hot")).isEqualTo("value");
    }

    @Test
    void overflowEvictsEldestAndWritesBack() {
        List<String> evicted = new ArrayList<>();
        HotMemoryCache cache = new HotMemoryCache(Duration.ofMinutes(30), 512);
        cache.setWriteBack((k, v) -> evicted.add(k));
        String big = "x".repeat(120);   // 约 240B
        cache.put("a", big);
        cache.put("b", big);
        assertThat(cache.size()).isEqualTo(2);
        cache.put("c", big);            // 超过 512B，最久未用的 a 被写回逐出
        assertThat(cache.size()).isEqualTo(2);
        assertThat(evicted).containsExactly("a");
        assertThat(cache.get("b")).isEqualTo(big);
        assertThat(cache.get("c")).isEqualTo(big);
    }
}

package com.agent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMemoryStoreTest {

    /** 包内假 Redis：HashMap + TTL 忽略（过期语义由 HotMemoryCacheTest 覆盖）。 */
    private static final class FakeRedis implements RedisFacade {
        final Map<String, String> data = new HashMap<>();

        public void setex(String key, String value, Duration ttl) {
            data.put(key, value);
        }

        public void set(String key, String value) {
            data.put(key, value);
        }

        public String get(String key) {
            return data.get(key);
        }

        public boolean del(String key) {
            return data.remove(key) != null;
        }

        public long incr(String key) {
            long v = Long.parseLong(data.getOrDefault(key, "0")) + 1;
            data.put(key, String.valueOf(v));
            return v;
        }

        public Set<String> keys(String pattern) {
            String prefix = pattern.substring(0, pattern.indexOf('*'));
            Set<String> out = new HashSet<>();
            for (String k : data.keySet()) {
                if (k.startsWith(prefix)) {
                    out.add(k);
                }
            }
            return out;
        }
    }

    @Test
    void threeTierSaveLoadAndLongTermFile(@TempDir Path dir) {
        FakeRedis redis = new FakeRedis();
        LongTermMemoryRepository files = new LongTermMemoryRepository(dir);
        DefaultMemoryStore store = new DefaultMemoryStore(redis, MemoryConfig.defaults(), files);

        // 临时：Redis + TTL 键位（值为 JSON 序列化）
        store.save("openai:m", "conv-1", "Q: hi A: hello", MemoryTier.TEMPORARY);
        assertThat(store.load("openai:m", "conv-1", String.class, MemoryTier.TEMPORARY))
                .hasValueSatisfying(v -> assertThat(v).contains("hello"));

        // 长期：正文落文件 + Redis 只存索引
        store.save("openai:m", "fact-1", "用户偏好简洁回答", MemoryTier.LONG_TERM);
        assertThat(store.load("openai:m", "fact-1", String.class, MemoryTier.LONG_TERM))
                .hasValueSatisfying(v -> assertThat(v).contains("用户偏好简洁回答"));
        assertThat(files.read("openai:m", "fact-1")).isPresent();

        // forget 同时清理
        store.forget("openai:m", "fact-1", MemoryTier.LONG_TERM);
        assertThat(store.load("openai:m", "fact-1", String.class, MemoryTier.LONG_TERM)).isEmpty();
        assertThat(files.read("openai:m", "fact-1")).isEmpty();

        // 未写入的层返回 empty
        assertThat(store.load("openai:m", "conv-1", String.class, MemoryTier.SHORT_TERM)).isEqualTo(Optional.empty());
    }

    @Test
    void reverseOptimizerConflictPolicy(@TempDir Path dir) {
        FakeRedis redis = new FakeRedis();
        MemoryConfig off = MemoryConfig.builder()
                .database(15).reverseOptimize(false)
                .consolidationMinRounds(100)
                .cacheTtl(Duration.ofMinutes(30)).cacheMaxSizeBytes(1024 * 1024)
                .temporaryTtl(Duration.ofDays(3)).shortTermTtl(Duration.ofDays(7))
                .build();
        DefaultMemoryStore store = new DefaultMemoryStore(redis, off,
                new LongTermMemoryRepository(dir));
        store.save("ns", "k", "旧记忆", MemoryTier.TEMPORARY);
        store.save("ns", "k", "用户说的新事实", MemoryTier.TEMPORARY);   // 冲突，开关关闭 → 保留旧记忆
        assertThat(store.load("ns", "k", String.class, MemoryTier.TEMPORARY))
                .hasValueSatisfying(v -> assertThat(v).contains("旧记忆"));

        MemoryConfig on = MemoryConfig.builder()
                .database(15).reverseOptimize(true)
                .consolidationMinRounds(100)
                .cacheTtl(Duration.ofMinutes(30)).cacheMaxSizeBytes(1024 * 1024)
                .temporaryTtl(Duration.ofDays(3)).shortTermTtl(Duration.ofDays(7))
                .build();
        DefaultMemoryStore overwrite = new DefaultMemoryStore(redis, on,
                new LongTermMemoryRepository(dir));
        overwrite.save("ns", "k", "用户说的新事实", MemoryTier.TEMPORARY);   // 冲突，开关开启 → 以用户为准
        assertThat(overwrite.load("ns", "k", String.class, MemoryTier.TEMPORARY))
                .hasValueSatisfying(v -> assertThat(v).contains("用户说的新事实"));
    }

    @Test
    void roundCountingAndListTemporary(@TempDir Path dir) {
        FakeRedis redis = new FakeRedis();
        DefaultMemoryStore store = new DefaultMemoryStore(redis, MemoryConfig.defaults(),
                new LongTermMemoryRepository(dir));
        store.save("ns", "c1", "第一轮", MemoryTier.TEMPORARY);
        store.save("ns", "c2", "第二轮", MemoryTier.TEMPORARY);
        assertThat(store.bumpRound("ns")).isEqualTo(1);
        assertThat(store.bumpRound("ns")).isEqualTo(2);
        assertThat(store.rounds("ns")).isEqualTo(2);
        assertThat(store.listTemporary("ns")).hasSize(2)
                .anySatisfy(item -> assertThat(item).startsWith("c1=").contains("第一轮"));
        store.resetRounds("ns");
        assertThat(store.rounds("ns")).isZero();
    }
}

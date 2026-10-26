package com.agent.memory;

import com.agent.util.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 记忆整合浓缩：轮次达到阈值或定时触发时，后台把临时记忆交给大模型
 * （由外部注入的 summarizer，通常为默认模型）整合浓缩，升级为长期记忆。
 * 异步执行，不阻塞对话。
 */
public final class MemoryConsolidator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    private final DefaultMemoryStore store;
    private final MemoryConfig config;
    private final Function<List<String>, String> summarizer;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(ThreadFactoryBuilder.build("agent-memory-consolidate"));
    private final AtomicBoolean running = new AtomicBoolean();

    public MemoryConsolidator(DefaultMemoryStore store, MemoryConfig config,
                              Function<List<String>, String> summarizer) {
        this.store = store;
        this.config = config;
        this.summarizer = summarizer;
    }

    /** 轮次达标则异步触发一次整合。 */
    public void maybeConsolidate(String namespace) {
        long rounds = store.rounds(namespace);
        if (rounds > 0 && rounds >= config.consolidationMinRounds() && running.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    consolidate(namespace);
                } catch (Exception e) {
                    log.warn("记忆整合浓缩失败 namespace={}: {}", namespace, e.getMessage());
                } finally {
                    running.set(false);
                }
            });
        }
    }

    /** 同步执行一次整合（暴露给定时任务）。 */
    public void consolidate(String namespace) {
        List<String> temporary = store.listTemporary(namespace);
        if (temporary.isEmpty()) {
            return;
        }
        String summary = summarizer.apply(temporary);
        if (summary == null || summary.isBlank()) {
            return;
        }
        String key = "consolidated-" + System.currentTimeMillis();
        store.save(namespace, key, summary, MemoryTier.LONG_TERM);
        store.resetRounds(namespace);
        log.info("记忆整合完成 namespace={} 条目={} 长期记忆 key={}", namespace, temporary.size(), key);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

package com.agent.pipeline.impl;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.memory.DefaultMemoryStore;
import com.agent.memory.MemoryConsolidator;
import com.agent.memory.MemoryStore;
import com.agent.memory.MemoryTier;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.InterceptorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆功能拦截器：请求前按 内存热记忆→临时→短期→长期 顺序装配记忆上下文；
 * 响应后把用户输入与回答写入临时记忆，轮次达标时后台触发整合浓缩。
 * <p>需要定制记忆读写逻辑时，继承本类并覆盖 {@link #loadMemory} / {@link #saveMemory}，
 * 装配与触发时机保持不变。</p>
 */
public class MemoryInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(MemoryInterceptor.class);

    protected final MemoryStore store;
    protected final MemoryConsolidator consolidator;   // 可为 null（无 Redis 时不会装配本拦截器）

    public MemoryInterceptor(MemoryStore store, MemoryConsolidator consolidator) {
        this.store = store;
        this.consolidator = consolidator;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
        return Mono.fromCallable(() -> injectMemory(in, ctx))
                .subscribeOn(com.agent.util.SdkSchedulers.io());
    }

    private ChatRequest injectMemory(ChatRequest in, InterceptorContext ctx) {
        String ns = ctx.namespace();
        String conv = ctx.conversationId();
        StringBuilder memory = new StringBuilder();
        // 查询顺序：热内存 → 临时 → 短期 → 长期，命中即止
        MemoryTier[] tiers = {MemoryTier.TEMPORARY, MemoryTier.SHORT_TERM, MemoryTier.LONG_TERM};
        String[] labels = {"临时", "短期", "长期"};
        for (int i = 0; i < tiers.length && memory.isEmpty(); i++) {
            String value = loadMemory(ns, conv, tiers[i]);
            if (value != null && !value.isBlank()) {
                memory.append('[').append(labels[i]).append("] ").append(value).append('\n');
            }
        }
        if (memory.isEmpty()) {
            return in;
        }
        String injected = "# 记忆上下文\n" + memory + "\n\n";
        List<Message> messages = new ArrayList<>(in.messages());
        if (!messages.isEmpty() && messages.get(0).role() == Message.Role.SYSTEM) {
            Message sys = messages.get(0);
            messages.set(0, Message.system(sys.content() == null ? injected : injected + sys.content()));
        } else {
            messages.add(0, Message.system(injected));
        }
        return in.toBuilder().messages(messages).build();
    }

    @Override
    public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
        StringBuilder content = new StringBuilder();
        return events
                .doOnNext(e -> {
                    if (e instanceof ChatEvent.TokenEvent t && t.delta() != null) {
                        content.append(t.delta());
                    }
                })
                .doFinally(signal -> {
                    if (signal != reactor.core.publisher.SignalType.ON_COMPLETE) {
                        return;
                    }
                    try {
                        String ns = ctx.namespace();
                        String conv = ctx.conversationId();
                        String userPrompt = lastUserPrompt(ctx);
                        saveMemory(ns, conv, "Q: " + userPrompt + "\nA: " + content);
                        if (store instanceof DefaultMemoryStore dms) {
                            dms.bumpRound(ns);
                            if (consolidator != null) {
                                consolidator.maybeConsolidate(ns);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("记忆写入失败: {}", e.getMessage());
                    }
                });
    }

    /** 读取某层记忆（默认走 MemoryStore，可覆盖以替换读取来源）。 */
    protected String loadMemory(String namespace, String key, MemoryTier tier) {
        return store.load(namespace, key, String.class, tier).orElse(null);
    }

    /** 保存临时记忆（默认走 MemoryStore，可覆盖以替换写入目标）。 */
    protected void saveMemory(String namespace, String key, String content) {
        store.save(namespace, key, content, MemoryTier.TEMPORARY);
    }

    private String lastUserPrompt(InterceptorContext ctx) {
        return ctx.<String>attr("memory.userPrompt");
    }
}

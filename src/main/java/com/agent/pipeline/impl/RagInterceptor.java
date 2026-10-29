package com.agent.pipeline.impl;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.InterceptorContext;
import com.agent.rag.RagHit;
import com.agent.rag.RagOptions;
import com.agent.rag.RagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 功能拦截器：检索外部知识库，把召回片段注入 system 上下文。
 *
 * <p>知识库的查询、过滤、组装全程可介入重载：</p>
 * <ul>
 *   <li>{@link #buildQuery}：从请求提取检索词（默认最后一条 user 消息）</li>
 *   <li>{@link #retrieve}：执行检索（默认 RagStore 混合检索）</li>
 *   <li>{@link #filter}：按请求级 minScore 等过滤命中</li>
 *   <li>{@link #formatHits}：把命中组装为注入文本（默认 Markdown 列表）</li>
 * </ul>
 * <p>请求级选项（{@code conversation.rag().maxResults(n).minScore(x)}）经上下文覆盖全局默认。</p>
 */
public class RagInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(RagInterceptor.class);

    /** 请求级 RAG 选项在 InterceptorContext 中的键。 */
    public static final String OPTIONS_KEY = "rag.options";

    protected final RagStore store;
    protected final int topK;

    public RagInterceptor(RagStore store, int topK) {
        this.store = store;
        this.topK = topK <= 0 ? 4 : topK;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
        String query = buildQuery(in, ctx);
        if (query == null || query.isBlank()) {
            return Mono.just(in);
        }
        return Mono.fromCallable(() -> {
                    RagOptions options = ctx.<RagOptions>attr(OPTIONS_KEY);
                    if (options == null) {
                        options = RagOptions.DEFAULT;
                    }
                    int effectiveTopK = options.maxResultsOr(topK);
                    List<RagHit> hits = retrieve(query, effectiveTopK);
                    hits = filter(hits, options.minScoreOr(0.0));
                    if (hits == null || hits.isEmpty()) {
                        return in;
                    }
                    String injected = "# 知识库召回\n" + formatHits(hits) + "\n\n";
                    List<Message> messages = new ArrayList<>(in.messages());
                    if (!messages.isEmpty() && messages.get(0).role() == Message.Role.SYSTEM) {
                        Message sys = messages.get(0);
                        messages.set(0, Message.system(sys.content() == null ? injected : injected + sys.content()));
                    } else {
                        messages.add(0, Message.system(injected));
                    }
                    return in.toBuilder().messages(messages).build();
                })
                .subscribeOn(com.agent.util.SdkSchedulers.io())
                .onErrorResume(e -> {
                    log.warn("RAG 检索失败（跳过注入）: {}", e.getMessage());
                    return Mono.just(in);
                });
    }

    @Override
    public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
        return events;
    }

    // ------------------------------------------------------------ 可重载钩子：查询 → 检索 → 过滤 → 组装

    /** 从请求提取检索词（默认最后一条 user 消息），返回 null 跳过 RAG。 */
    protected String buildQuery(ChatRequest in, InterceptorContext ctx) {
        List<Message> messages = in.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Message.Role.USER) {
                return messages.get(i).content();
            }
        }
        return null;
    }

    /** 执行检索（默认 RagStore 混合检索），可覆盖以替换检索实现。 */
    protected List<RagHit> retrieve(String query, int topK) {
        return store.hybridSearch(query, topK);
    }

    /** 过滤命中（默认按 minScore 截断），可覆盖实现业务过滤（如黑白名单、租户隔离）。 */
    protected List<RagHit> filter(List<RagHit> hits, double minScore) {
        if (hits == null || hits.isEmpty() || minScore <= 0) {
            return hits;
        }
        return hits.stream().filter(h -> h.score() >= minScore).collect(Collectors.toList());
    }

    /** 把命中组装为注入文本（默认 Markdown 列表），可覆盖实现自定义模板。 */
    protected String formatHits(List<RagHit> hits) {
        return hits.stream().map(h -> "- " + h.text()).collect(Collectors.joining("\n"));
    }
}

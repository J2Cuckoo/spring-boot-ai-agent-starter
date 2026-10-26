package com.agent.pipeline.impl;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.InterceptorContext;
import com.agent.rag.RagHit;
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
 * <p>需要定制检索逻辑时，继承本类并覆盖 {@link #retrieve}，注入时机与拼接方式保持不变。</p>
 */
public class RagInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(RagInterceptor.class);

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
        String query = lastUserQuery(in);
        if (query == null || query.isBlank()) {
            return Mono.just(in);
        }
        String q = query;
        return Mono.fromCallable(() -> {
                    List<RagHit> hits = retrieve(q, topK);
                    if (hits == null || hits.isEmpty()) {
                        return in;
                    }
                    String snippets = hits.stream()
                            .map(h -> "- " + h.text())
                            .collect(Collectors.joining("\n"));
                    String injected = "# 知识库召回\n" + snippets + "\n\n";
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

    /** 检索召回片段（默认走 RagStore 混合检索，可覆盖以替换检索实现）。 */
    protected List<RagHit> retrieve(String query, int topK) {
        return store.hybridSearch(query, topK);
    }

    private String lastUserQuery(ChatRequest in) {
        List<Message> messages = in.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Message.Role.USER) {
                return messages.get(i).content();
            }
        }
        return null;
    }
}

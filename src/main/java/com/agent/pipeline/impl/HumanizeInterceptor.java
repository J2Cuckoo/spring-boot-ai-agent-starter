package com.agent.pipeline.impl;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.InterceptorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真人门禁（后置拦截器，去 AI 味）：响应后聚合文本，交给 {@code spring.agent.gate.model}
 * 指定的门禁模型改写为自然的人类口吻。门禁模型自身的输出不做二次门禁（避免递归）。
 * 注意：开启后流式输出会先聚合再整体返回，这是该模式的固有语义。
 * <p>需要定制去 AI 味逻辑时，继承本类并覆盖 {@link #humanize}。</p>
 */
public class HumanizeInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(HumanizeInterceptor.class);

    private static final String PROMPT = """
            把下面的文本改写为自然的人类口吻：去掉模板化的句式与空洞的修饰，\
            保留全部事实信息与要点，语气可自然随意一些。直接输出改写结果，不要任何解释。""";

    protected final boolean enabled;
    protected final String gateModel;     // provider:model，开启时必填

    public HumanizeInterceptor(boolean enabled, String gateModel) {
        this.enabled = enabled;
        this.gateModel = gateModel;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
        return Mono.just(in);
    }

    @Override
    public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
        if (!enabled) {
            return events;
        }
        if (gateModel == null || gateModel.isBlank()) {
            return Flux.error(new IllegalStateException(
                    "spring.agent.gate.human-gate=true 但未配置 spring.agent.gate.model（门禁模型），"
                            + "请在配置中补全后重试"));
        }
        // 门禁模型自身不做二次门禁
        if (gateModel.equals(ctx.namespace())) {
            return events;
        }
        StringBuilder content = new StringBuilder();
        AtomicReference<ChatEvent.UsageEvent> usage = new AtomicReference<>();
        AtomicReference<ChatEvent.DoneEvent> done = new AtomicReference<>();
        AtomicBoolean sawText = new AtomicBoolean(false);
        return events
                .doOnNext(e -> {
                    if (e instanceof ChatEvent.TokenEvent t) {
                        sawText.set(true);
                        content.append(t.delta());
                    } else if (e instanceof ChatEvent.UsageEvent u) {
                        usage.set(u);
                    } else if (e instanceof ChatEvent.DoneEvent d) {
                        done.set(d);
                    }
                })
                .collectList()
                .flatMapMany(original -> {
                    if (!sawText.get()) {
                        return Flux.fromIterable(original);
                    }
                    return Mono.fromCallable(() -> humanize(content.toString(), ctx))
                            .subscribeOn(com.agent.util.SdkSchedulers.io())
                            .flatMapMany(rewritten -> {
                                List<ChatEvent> out = new ArrayList<>(3);
                                out.add(ChatEvent.token(rewritten));
                                ChatEvent.UsageEvent u = usage.get();
                                if (u != null) {
                                    out.add(u);
                                }
                                out.add(new ChatEvent.DoneEvent(
                                        done.get() == null ? "stop" : done.get().finishReason()));
                                return Flux.fromIterable(out);
                            })
                            .onErrorResume(e -> {
                                log.warn("真人门禁处理失败，返回原文: {}", e.getMessage());
                                return Flux.fromIterable(original);
                            });
                });
    }

    /** 去 AI 味处理（默认走门禁模型改写，可覆盖以替换处理逻辑）。 */
    protected String humanize(String content, InterceptorContext ctx) {
        return ctx.agent().chat(PROMPT + "\n\n" + content).using(gateModel).write();
    }
}

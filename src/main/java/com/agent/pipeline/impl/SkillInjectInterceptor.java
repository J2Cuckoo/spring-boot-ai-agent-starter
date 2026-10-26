package com.agent.pipeline.impl;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.pipeline.Interceptor;
import com.agent.pipeline.InterceptorContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 技能注入拦截器：把宿主提供的一份或多份 skill.md（或技能清单）组装进 system 上下文。
 * skill.md 由宿主应用提供（{@code spring.agent.skills.paths} 或宿主 classpath 的
 * {@code skills/*.skill.md}），SDK 自身不内置任何 skill.md。
 * 注入文本在构造期预计算，请求期零额外开销。
 */
public final class SkillInjectInterceptor implements Interceptor {

    private final List<String> skillDocs;
    private final String injected;

    public SkillInjectInterceptor(List<String> skillDocs) {
        this.skillDocs = skillDocs == null ? List.of() : List.copyOf(skillDocs);
        String joined = this.skillDocs.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n\n---\n\n"));
        this.injected = joined.isEmpty() ? "" : "# 可用技能\n\n" + joined + "\n\n";
    }

    /** 已装配的技能文档（测试与宿主诊断用）。 */
    public List<String> skillDocs() {
        return skillDocs;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public Mono<ChatRequest> before(ChatRequest in, InterceptorContext ctx) {
        if (injected.isEmpty()) {
            return Mono.just(in);
        }
        List<Message> messages = new ArrayList<>(in.messages());
        if (!messages.isEmpty() && messages.get(0).role() == Message.Role.SYSTEM) {
            Message sys = messages.get(0);
            messages.set(0, Message.system(injected + sys.content()));
        } else {
            messages.add(0, Message.system(injected));
        }
        ChatRequest.Builder builder = in.toBuilder();
        builder.messages(messages);
        return Mono.just(builder.build());
    }

    @Override
    public Flux<ChatEvent> after(Flux<ChatEvent> events, InterceptorContext ctx) {
        return events;
    }
}

package com.agent.executor;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.contract.ToolCall;
import com.agent.contract.ToolSpec;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import com.agent.pipeline.InterceptorChain;
import com.agent.pipeline.InterceptorContext;
import com.agent.protocol.ProtocolAdapter;
import com.agent.resilience.ResilienceManager;
import com.agent.tool.ToolExecutor;
import com.agent.tool.ToolSchemaValidator;
import com.agent.transport.HttpRequestSpec;
import com.agent.transport.NettyHttpClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行骨架（Template Method）：拦截 → 容错包裹 → 协议序列化 → 传输 → 事件解析 → 工具闭环。
 * 子类只提供 {@link ProtocolAdapter}。
 */
public abstract class AbstractModelExecutor {

    /** 工具调用闭环的最大轮数。 */
    static final int MAX_TOOL_DEPTH = 8;

    protected final NettyHttpClient http;
    protected final ResilienceManager resilience;
    protected final ToolExecutor tools;

    protected AbstractModelExecutor(NettyHttpClient http, ResilienceManager resilience, ToolExecutor tools) {
        this.http = http;
        this.resilience = resilience;
        this.tools = tools;
    }

    protected abstract ProtocolAdapter adapter();

    public Flux<ChatEvent> execute(Provider provider, ModelConfig model, ChatRequest request,
                                   InterceptorContext ctx, InterceptorChain pipeline) {
        boolean stream = Boolean.TRUE.equals(request.stream());
        // guard 的 defer 重订阅会重跑整个链（含拦截器），重试因此覆盖全链路
        return resilience.guard(model.ref(), Flux.defer(() ->
                pipeline.before(request, ctx)
                        .flatMapMany(prepared ->
                                pipeline.after(withToolLoop(provider, model, prepared, stream, 0), ctx))));
    }

    private Flux<ChatEvent> withToolLoop(Provider p, ModelConfig m, ChatRequest req, boolean stream, int depth) {
        if (depth >= MAX_TOOL_DEPTH || tools.isEmpty() || req.tools().isEmpty()) {
            return callRemote(p, m, req, stream);
        }
        return Flux.defer(() -> {
            List<ToolCall> pending = new CopyOnWriteArrayList<>();
            Flux<ChatEvent> pass = callRemote(p, m, req, stream)
                    .doOnNext(e -> {
                        if (e instanceof ChatEvent.ToolCallCompleteEvent t) {
                            pending.add(new ToolCall(t.id(), t.name(), t.arguments()));
                        }
                    });
            return pass.concatWith(Flux.defer(() -> {
                if (pending.isEmpty()) {
                    return Flux.empty();
                }
                return Mono.fromCallable(() -> appendToolResults(req, pending))
                        .subscribeOn(com.agent.util.SdkSchedulers.io())
                        .flatMapMany(next -> withToolLoop(p, m, next, stream, depth + 1));
            }));
        });
    }

    private ChatRequest appendToolResults(ChatRequest req, List<ToolCall> calls) {
        ChatRequest.Builder b = req.toBuilder();
        b.addMessage(Message.assistantWithTools("", calls));
        for (ToolCall call : calls) {
            List<String> errors = ToolSchemaValidator.validate(call.arguments(), findSpec(req, call.name()));
            String result = errors.isEmpty()
                    ? tools.execute(call)
                    : "参数校验失败: " + errors;
            b.addMessage(Message.tool(call.id(), result));
        }
        return b.build();
    }

    private ToolSpec findSpec(ChatRequest req, String name) {
        return req.tools().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Flux<ChatEvent> callRemote(Provider p, ModelConfig m, ChatRequest req, boolean stream) {
        return Flux.defer(() -> {
            ProtocolAdapter adapter = adapter();
            HttpRequestSpec spec = adapter.buildRequest(p, m, req, stream);
            // 网络 IO 在 Netty EventLoop；JSON 解析/事件组装切换到 SDK 专属 IO 线程池，
            // 大响应解析不占用 EventLoop，网络与业务完全解耦
            Flux<ChatEvent> events = stream
                    ? adapter.parse(http.executeStreaming(spec, adapter.frameMode()))
                        .publishOn(com.agent.util.SdkSchedulers.io())
                    : http.execute(spec)
                        .publishOn(com.agent.util.SdkSchedulers.io())
                        .flatMapMany(adapter::parseResponse);
            return ensureDone(events);
        });
    }

    /** 事件流正常结束但厂商未显式发 DoneEvent 时补一个。 */
    static Flux<ChatEvent> ensureDone(Flux<ChatEvent> events) {
        return Flux.defer(() -> {
            AtomicBoolean done = new AtomicBoolean(false);
            return events
                    .doOnNext(e -> {
                        if (e instanceof ChatEvent.DoneEvent) {
                            done.set(true);
                        }
                    })
                    .concatWith(Mono.defer(() -> done.get()
                            ? Mono.empty()
                            : Mono.just(new ChatEvent.DoneEvent("stop"))));
        });
    }
}

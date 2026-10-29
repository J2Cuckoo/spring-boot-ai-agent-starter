package com.agent;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.ChatResponse;
import com.agent.contract.Message;
import com.agent.contract.Tool;
import com.agent.contract.ToolCall;
import com.agent.contract.Usage;
import com.agent.executor.AbstractModelExecutor;
import com.agent.executor.ModelExecutorFactory;
import com.agent.memory.MemoryConsolidator;
import com.agent.memory.MemoryStore;
import com.agent.model.ModelConfig;
import com.agent.model.ModelRegistry;
import com.agent.model.Provider;
import com.agent.output.Conversation;
import com.agent.pipeline.InterceptorChain;
import com.agent.pipeline.InterceptorContext;
import com.agent.pipeline.impl.MemoryInterceptor;
import com.agent.pipeline.impl.RagInterceptor;
import com.agent.pool.ChatEventPool;
import com.agent.pool.PooledTokenEvent;
import com.agent.rag.RagStore;
import com.agent.resilience.ResilienceManager;
import com.agent.tool.ToolExecutor;
import com.agent.transport.NettyHttpClient;
import com.agent.workflow.WorkflowBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 对外唯一门面：选择 provider:model、组织输入、暴露四种输出终结方式。
 * 作为 Spring Boot Starter 装配时读取宿主 {@code spring.agent.*} 配置。
 */
public final class Agent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final AgentConfig config;
    private final NettyHttpClient http;
    private final ResilienceManager resilience;
    private final ToolExecutor toolExecutor;
    private final InterceptorChain pipeline;
    /** 执行器按 ProviderType 实例级缓存：避免每请求分配（生命周期随 Agent，不跨 Agent 共享）。 */
    private final java.util.concurrent.ConcurrentHashMap<com.agent.model.ProviderType, AbstractModelExecutor> executors =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 聚合路径的 TokenEvent 池走 ThreadLocal：线程内复用、无跨线程锁竞争。 */
    private static final ThreadLocal<ChatEventPool> TOKEN_POOL =
            ThreadLocal.withInitial(() -> new ChatEventPool(64));

    Agent(AgentConfig config) {
        this.config = config;
        this.http = new NettyHttpClient(config.transport());
        this.resilience = new ResilienceManager(config.resilience());
        this.toolExecutor = new ToolExecutor();
        List<com.agent.pipeline.Interceptor> interceptors = new ArrayList<>(config.interceptors());
        // Builder 模式下注册了 MemoryStore/RagStore 时自动补齐内置拦截器（Spring 装配在 AutoConfiguration 完成）
        MemoryStore memory = config.memoryStore();
        if (memory != null && interceptors.stream().noneMatch(i -> i instanceof MemoryInterceptor)) {
            MemoryConsolidator consolidator = config.memoryConsolidator();
            interceptors.add(new MemoryInterceptor(memory, consolidator));
        }
        RagStore rag = config.ragStore();
        if (rag != null && interceptors.stream().noneMatch(i -> i instanceof RagInterceptor)) {
            interceptors.add(new RagInterceptor(rag, config.ragTopK()));
        }
        this.pipeline = new InterceptorChain(interceptors);
    }

    // ------------------------------------------------------------ 门面 API

    /** 不触发调用，返回 Conversation，必须接终结方式。 */
    public Conversation chat(String prompt) {
        return new Conversation(this, prompt);
    }

    public Conversation chat(ChatRequest request) {
        return new Conversation(this, request);
    }

    public String write(String prompt) {
        return chat(prompt).write();
    }

    public <T> T write(String prompt, Class<T> type) {
        return chat(prompt).write(type);
    }

    public com.agent.output.AgentFile write(String prompt, com.agent.output.AgentFileRef fileRef) {
        return chat(prompt).write(fileRef);
    }

    /** 工作流编排入口。 */
    public WorkflowBuilder newWorkflow() {
        return new WorkflowBuilder(this);
    }

    /** 注册工具处理函数（工具闭环自动执行）。 */
    public void registerTool(String name, Function<ToolCall, String> handler) {
        toolExecutor.register(name, handler);
    }

    /** 声明式注册工具：直接传入 {@link Tool} 实现，内部转为执行逻辑。 */
    public void registerTool(Tool tool) {
        registerTool(tool.name(), call -> tool.execute(call.arguments()));
    }

    public ToolExecutor tools() {
        return toolExecutor;
    }

    public ResilienceManager resilience() {
        return resilience;
    }

    public AgentConfig config() {
        return config;
    }

    @Override
    public void close() {
        MemoryConsolidator consolidator = config.memoryConsolidator();
        if (consolidator != null) {
            consolidator.close();
        }
        http.close();
        log.debug("Agent 已关闭");
    }

    // ------------------------------------------------------------ 执行内部

    public ModelConfig resolveModel(Conversation c) {
        String ref = c.using() != null ? c.using() : config.defaultModel();
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException("未指定模型：请配置 spring.agent.default-model 或使用 .using(\"provider:model\")");
        }
        return config.registry().resolve(ref);
    }

    public String resolvedRef(Conversation c) {
        return resolveModel(c).ref();
    }

    public Flux<ChatEvent> executeEvents(Conversation c, boolean stream) {
        ModelConfig model = resolveModel(c);
        Provider provider = config.registry().providers().require(model.provider());
        ChatRequest request = c.toRequest(stream, false);
        return doExecute(provider, model, request, c);
    }

    public ChatResponse blockingResponse(Conversation c, boolean json) {
        ModelConfig model = resolveModel(c);
        Provider provider = config.registry().providers().require(model.provider());
        ChatRequest request = c.toRequest(false, json);
        List<ChatEvent> events = doExecute(provider, model, request, c).collectList().block();
        return aggregate(events);
    }

    private Flux<ChatEvent> doExecute(Provider provider, ModelConfig model, ChatRequest request) {
        return doExecute(provider, model, request, null);
    }

    private Flux<ChatEvent> doExecute(Provider provider, ModelConfig model, ChatRequest request,
                                      Conversation conversation) {
        InterceptorContext ctx = new InterceptorContext(model.ref(), model, this);
        String userPrompt = lastUserPrompt(request);
        if (userPrompt != null) {
            ctx.attr("memory.userPrompt", userPrompt);
        }
        if (conversation != null) {
            // 请求级 RAG / 记忆选项：经上下文传递给对应功能拦截器
            ctx.attr(com.agent.pipeline.impl.RagInterceptor.OPTIONS_KEY, conversation.ragOptions());
            ctx.attr(com.agent.pipeline.impl.MemoryInterceptor.OPTIONS_KEY, conversation.memoryOptions());
        }
        AbstractModelExecutor executor = executors.computeIfAbsent(provider.type(),
                t -> ModelExecutorFactory.create(t, http, resilience, toolExecutor));
        return executor.execute(provider, model, request, ctx, pipeline);
    }

    private String lastUserPrompt(ChatRequest request) {
        List<Message> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Message.Role.USER) {
                return messages.get(i).content();
            }
        }
        return null;
    }

    static ChatResponse aggregate(List<ChatEvent> events) {
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        Usage usage = Usage.zero();
        String finishReason = "stop";
        boolean sawDone = false;
        // TokenEvent 高频短命：聚合路径经 ThreadLocal 对象池复用载体，无跨线程竞争
        ChatEventPool pool = TOKEN_POOL.get();
        if (events != null) {
            for (ChatEvent e : events) {
                if (e instanceof ChatEvent.TokenEvent t) {
                    PooledTokenEvent pooled = pool.borrow(t.delta() == null ? "" : t.delta(), t.timestamp());
                    content.append(pooled.delta());
                    pool.recycle(pooled);
                } else if (e instanceof ChatEvent.ToolCallCompleteEvent tc) {
                    toolCalls.add(new ToolCall(tc.id(), tc.name(), tc.arguments()));
                } else if (e instanceof ChatEvent.UsageEvent u) {
                    usage = usage.plus(new Usage(u.promptTokens(), u.completionTokens(), u.totalTokens()));
                } else if (e instanceof ChatEvent.DoneEvent d) {
                    finishReason = d.finishReason();
                    sawDone = true;
                }
            }
        }
        return new ChatResponse(content.toString(), toolCalls, usage, finishReason, !sawDone);
    }

    // ------------------------------------------------------------ Builder

    public static Builder builder() {
        return new Builder();
    }

    /** 门面 Builder：委托 AgentConfig.Builder，build 产出 Agent。 */
    public static final class Builder {

        private final AgentConfig.Builder cb = AgentConfig.builder();
        private final java.util.List<Tool> tools = new ArrayList<>();

        public Builder registerProvider(Provider provider) {
            cb.registerProvider(provider);
            return this;
        }

        public Builder registerModel(ModelConfig model) {
            cb.registerModel(model);
            return this;
        }

        public Builder defaultModel(String providerModel) {
            cb.defaultModel(providerModel);
            return this;
        }

        public Builder modelRegistry(ModelRegistry modelRegistry) {
            cb.modelRegistry(modelRegistry);
            return this;
        }

        public Builder addInterceptor(com.agent.pipeline.Interceptor interceptor) {
            cb.addInterceptor(interceptor);
            return this;
        }

        public Builder transport(com.agent.transport.NettyHttpConfig config) {
            cb.transport(config);
            return this;
        }

        public Builder resilience(com.agent.resilience.ResilienceConfig config) {
            cb.resilience(config);
            return this;
        }

        public Builder memoryStore(MemoryStore store) {
            cb.memoryStore(store);
            return this;
        }

        public Builder memoryConfig(com.agent.memory.MemoryConfig config) {
            cb.memoryConfig(config);
            return this;
        }

        public Builder memoryConsolidator(MemoryConsolidator consolidator) {
            cb.memoryConsolidator(consolidator);
            return this;
        }

        public Builder ragStore(RagStore store) {
            cb.ragStore(store);
            return this;
        }

        public Builder ragTopK(int topK) {
            cb.ragTopK(topK);
            return this;
        }

        public Builder gate(boolean enabled, String model) {
            cb.gate(enabled, model);
            return this;
        }

        public Builder skillManager(com.agent.skill.SkillManager manager) {
            cb.skillManager(manager);
            return this;
        }

        /** 声明式注册工具（纯 Java 场景），Spring 场景下由自动装配收集 {@code Tool} Bean 更便捷。 */
        public Builder tool(Tool tool) {
            tools.add(tool);
            return this;
        }

        public Agent build() {
            Agent agent = new Agent(cb.build());
            tools.forEach(agent::registerTool);
            return agent;
        }
    }
}

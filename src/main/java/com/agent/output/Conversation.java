package com.agent.output;

import com.agent.Agent;
import com.agent.contract.ChatRequest;
import com.agent.contract.ChatResponse;
import com.agent.contract.Message;
import com.agent.contract.ToolSpec;
import com.agent.serialize.JacksonConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 输出终结 DSL：中间配置方法后必须接且只接一种终结方式。
 * <pre>{@code
 * agent.chat("你好").systemTemplate("你是{role}").variable("role","架构师")
 *      .temperature(0.3).write();
 * }</pre>
 */
public final class Conversation {

    private final Agent agent;
    private final String prompt;
    private final ChatRequest base;           // chat(ChatRequest) 形态
    private final List<String> extraSystem = new ArrayList<>();

    private String system;
    private String systemTemplate;
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private Double temperature;
    private Integer maxTokens;
    private String using;
    private final List<ToolSpec> tools = new ArrayList<>();
    private boolean terminated;
    private boolean streamMode;

    public Conversation(Agent agent, String prompt) {
        this.agent = agent;
        this.prompt = prompt;
        this.base = null;
    }

    public Conversation(Agent agent, ChatRequest base) {
        this.agent = agent;
        this.prompt = null;
        this.base = base;
    }

    // ------------------------------------------------------------ 中间配置

    public Conversation system(String system) {
        requireActive();
        this.system = system;
        return this;
    }

    public Conversation systemTemplate(String template) {
        requireActive();
        this.systemTemplate = template;
        return this;
    }

    public Conversation variable(String key, Object value) {
        requireActive();
        variables.put(key, value);
        return this;
    }

    public Conversation variables(Map<String, Object> vars) {
        requireActive();
        variables.putAll(vars);
        return this;
    }

    public Conversation temperature(double t) {
        requireActive();
        this.temperature = t;
        return this;
    }

    public Conversation maxTokens(int n) {
        requireActive();
        this.maxTokens = n;
        return this;
    }

    public Conversation tool(ToolSpec tool) {
        requireActive();
        tools.add(tool);
        return this;
    }

    /** 声明式工具：直接传 {@link com.agent.contract.Tool} 实现，内部取 {@code tool.spec()}。 */
    public Conversation tool(com.agent.contract.Tool tool) {
        requireActive();
        tools.add(tool.spec());
        return this;
    }

    public Conversation using(String providerModel) {
        requireActive();
        this.using = providerModel;
        return this;
    }

    /** 用 {@link com.agent.model.ModelRef} 配置对象指定模型，替代硬编码字符串。 */
    public Conversation using(com.agent.model.ModelRef modelRef) {
        requireActive();
        this.using = modelRef.ref();
        return this;
    }

    // ------------------------------------------------------------ 请求级 RAG / 记忆设置

    private RagSettings ragSettings;
    private MemorySettings memorySettings;

    /** 请求级 RAG 设置：{@code .rag().maxResults(5).minScore(0.6)...}（须全局已装配 RAG 拦截器）。 */
    public RagSettings rag() {
        requireActive();
        if (ragSettings == null) {
            ragSettings = new RagSettings(this);
        }
        return ragSettings;
    }

    /** consumer 形式：{@code .rag(r -> r.maxResults(5))}，配置完回到 Conversation。 */
    public Conversation rag(java.util.function.Consumer<RagSettings> configurer) {
        requireActive();
        configurer.accept(rag());
        return this;
    }

    /** 请求级记忆设置：{@code .memory().tier(MemoryTier.SHORT_TERM)...}（须全局已装配记忆拦截器）。 */
    public MemorySettings memory() {
        requireActive();
        if (memorySettings == null) {
            memorySettings = new MemorySettings(this);
        }
        return memorySettings;
    }

    /** consumer 形式：{@code .memory(m -> m.tier(MemoryTier.LONG_TERM))}，配置完回到 Conversation。 */
    public Conversation memory(java.util.function.Consumer<MemorySettings> configurer) {
        requireActive();
        configurer.accept(memory());
        return this;
    }

    /** 请求级 RAG 选项（未配置为默认）。 */
    public com.agent.rag.RagOptions ragOptions() {
        return ragSettings == null ? com.agent.rag.RagOptions.DEFAULT : ragSettings.toOptions();
    }

    /** 请求级记忆选项（未配置为默认）。 */
    public com.agent.memory.MemoryOptions memoryOptions() {
        return memorySettings == null ? com.agent.memory.MemoryOptions.DEFAULT : memorySettings.toOptions();
    }

    Conversation extraSystem(String instruction) {
        extraSystem.add(instruction);
        return this;
    }

    // ------------------------------------------------------------ 终结 API（互斥，write 永远是结尾）

    /** 阻塞聚合为纯字符串（默认写法）。 */
    public String write() {
        requireActive();
        markTerminated();
        return agent.blockingResponse(this, false).content();
    }

    /** 阻塞，注入 JSON 约束并反序列化为目标类型；失败自动纠偏一次，仍失败抛 {@link JsonOutputException}。 */
    public <T> T write(Class<T> type) {
        requireActive();
        markTerminated();
        return blockingJson(type);
    }

    /** 阻塞，以响应信封格式写文件，返回 {@link AgentFile} 元数据。 */
    public AgentFile write(AgentFileRef fileRef) {
        requireActive();
        markTerminated();
        return blockingFile(fileRef);
    }

    /** 进入流式订阅阶段：{@code .stream().subscribe(回调).write()}——
     * subscribe 声明在 stream 之后、write 之前，write 仍是绝对结尾（阻塞至流消费完毕）。 */
    public StreamingConversation stream() {
        requireActive();
        this.streamMode = true;
        return new StreamingConversation(this);
    }

    // ------------------------------------------------------------ 内部复用（供流式句柄与终结方法共享）

    <T> T blockingJson(Class<T> type) {
        // 注解驱动的字段约束：目标 POJO 的字段必须标注 @AgentJsonFormatFiled（AgentJsonSchema 负责校验）
        String schema = AgentJsonSchema.schemaInstructionFor(type);
        Conversation target = schema == null ? this : this.extraSystem(schema);
        String raw = agent.blockingResponse(target, true).content();
        try {
            return JacksonConfig.mapper().readValue(stripCodeFence(raw), type);
        } catch (Exception first) {
            String hint = "上一次输出不是合法 JSON（" + first.getMessage() + "）。请只输出符合该结构的合法 JSON，"
                    + "不要任何解释、注释或 Markdown 代码块标记。";
            String retry = agent.blockingResponse(target.extraSystem(hint), true).content();
            try {
                return JacksonConfig.mapper().readValue(stripCodeFence(retry), type);
            } catch (Exception second) {
                throw new JsonOutputException(second.getMessage(), raw);
            }
        }
    }

    AgentFile blockingFile(AgentFileRef fileRef) {
        ChatResponse resp = agent.blockingResponse(this, false);
        String ref = agent.resolvedRef(this);
        String provider = ref.contains(":") ? ref.substring(0, ref.indexOf(':')) : ref;
        ResponseEnvelope envelope = new ResponseEnvelope(
                "text",
                ref,
                provider,
                resp.content(),
                resp.toolCalls(),
                resp.usage(),
                resp.finishReason(),
                Instant.now().toString());
        return new ResponseFileWriter().write(fileRef, envelope);
    }

    // ------------------------------------------------------------ 内部

    public Agent agent() {
        return agent;
    }

    public String using() {
        return using;
    }

    public ChatRequest toRequest(boolean stream, boolean json) {
        ChatRequest.Builder b = base != null ? base.toBuilder() : ChatRequest.builder();
        if (base == null) {
            b.addMessage(Message.user(prompt));
        }
        String sys = system != null ? system
                : systemTemplate != null ? renderTemplate(systemTemplate, variables)
                : null;
        StringBuilder sb = new StringBuilder();
        if (sys != null) {
            sb.append(sys).append('\n');
        }
        for (String extra : extraSystem) {
            sb.append(extra).append('\n');
        }
        if (json) {
            sb.append("请只输出合法 JSON，不要任何解释或 Markdown 代码块标记。");
        }
        String fullSystem = sb.length() == 0 ? null : sb.toString();
        if (fullSystem != null) {
            List<Message> messages = new ArrayList<>(b.build().messages());
            if (!messages.isEmpty() && messages.get(0).role() == Message.Role.SYSTEM) {
                messages.set(0, Message.system(messages.get(0).content() + "\n" + fullSystem));
            } else {
                messages.add(0, Message.system(fullSystem));
            }
            b.messages(messages);
        }
        b.stream(stream);
        if (json) {
            b.responseFormat("json");
        }
        if (temperature != null) {
            b.temperature(temperature);
        }
        if (maxTokens != null) {
            b.maxTokens(maxTokens);
        }
        if (!tools.isEmpty()) {
            b.tools(tools);
        }
        return b.build();
    }

    private void requireActive() {
        if (terminated) {
            throw new IllegalStateException("Conversation 已终结：输出方式互斥，一条链只能以一种 write 收尾");
        }
        if (streamMode) {
            throw new IllegalStateException("已进入流式模式（.stream()），请在 .subscribe(...) 之后以 .write() 结尾");
        }
    }

    void markTerminated() {
        this.terminated = true;
    }

    static String renderTemplate(String template, Map<String, Object> vars) {
        String out = template;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    public static String stripCodeFence(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int firstLine = s.indexOf('\n');
            if (firstLine > 0) {
                s = s.substring(firstLine + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        return s;
    }
}

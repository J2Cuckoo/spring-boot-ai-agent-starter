package com.agent.output;

import com.agent.Agent;
import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.ChatResponse;
import com.agent.contract.Message;
import com.agent.contract.ToolSpec;
import com.agent.serialize.JacksonConfig;
import reactor.core.publisher.Flux;

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

    Conversation extraSystem(String instruction) {
        extraSystem.add(instruction);
        return this;
    }

    // ------------------------------------------------------------ 四种终结（互斥）

    /** 阻塞聚合为纯字符串。 */
    public String write() {
        markTerminated();
        return agent.blockingResponse(this, false).content();
    }

    /** 阻塞，注入 JSON 约束并反序列化为目标类型；失败自动纠偏一次，仍失败抛 {@link JsonOutputException}。 */
    public <T> T writeJson(Class<T> type) {
        markTerminated();
        String raw = agent.blockingResponse(this, true).content();
        try {
            return JacksonConfig.mapper().readValue(stripCodeFence(raw), type);
        } catch (Exception first) {
            String hint = "上一次输出不是合法 JSON（" + first.getMessage() + "）。请只输出符合该结构的合法 JSON，"
                    + "不要任何解释、注释或 Markdown 代码块标记。";
            String retry = agent.blockingResponse(this.extraSystem(hint), true).content();
            try {
                return JacksonConfig.mapper().readValue(stripCodeFence(retry), type);
            } catch (Exception second) {
                throw new JsonOutputException(second.getMessage(), raw);
            }
        }
    }

    /** 非阻塞，立即返回事件流。 */
    public Flux<ChatEvent> writeStream() {
        markTerminated();
        return agent.executeEvents(this, true);
    }

    /** 阻塞，以响应信封格式写文件。 */
    public void writeFile(String path, String fileName) {
        markTerminated();
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
        new ResponseFileWriter().write(path, fileName, envelope);
    }

    // ------------------------------------------------------------ 内部

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
            throw new IllegalStateException("Conversation 已终结：四种输出方式互斥，一条链只能以一种收尾");
        }
    }

    private void markTerminated() {
        requireActive();
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

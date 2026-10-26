package com.agent.tool;

import com.agent.contract.ToolCall;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 工具执行器：注册名 → 处理函数，执行结果以字符串回填给模型（Command 模式）。
 */
public final class ToolExecutor {

    private final Map<String, Function<ToolCall, String>> handlers = new ConcurrentHashMap<>();

    public void register(String name, Function<ToolCall, String> handler) {
        handlers.put(name, handler);
    }

    public boolean isRegistered(String name) {
        return handlers.containsKey(name);
    }

    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    /** 执行工具；未注册或执行异常时返回错误描述字符串（交由模型自行调整）。 */
    public String execute(ToolCall call) {
        Function<ToolCall, String> handler = handlers.get(call.name());
        if (handler == null) {
            return "工具未注册: " + call.name() + "（可用: " + handlers.keySet() + "）";
        }
        try {
            return handler.apply(call);
        } catch (Exception e) {
            return "工具执行失败: " + call.name() + " -> " + e.getMessage();
        }
    }
}

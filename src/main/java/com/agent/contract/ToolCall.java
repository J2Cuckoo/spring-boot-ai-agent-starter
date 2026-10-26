package com.agent.contract;

import java.util.Map;

/**
 * 一次完整的工具调用（参数已解析为对象）。
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

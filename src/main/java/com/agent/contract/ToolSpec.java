package com.agent.contract;

import java.util.Map;

/**
 * 工具定义：名称、描述与 JSON Schema 形式的参数说明。
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {

    public ToolSpec {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public static ToolSpec of(String name, String description, Map<String, Object> parameters) {
        return new ToolSpec(name, description, parameters);
    }
}

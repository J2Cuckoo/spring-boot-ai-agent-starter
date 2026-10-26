package com.agent.tool;

import com.agent.serialize.JacksonConfig;

import java.util.Map;

/**
 * 工具参数解析：厂商 arguments JSON 字符串 → Map。解析失败返回空 Map（不抛异常）。
 */
public final class ToolCallParser {

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Map.of();
        }
        try {
            return JacksonConfig.mapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ToolCallParser() {
    }
}

package com.agent.tool;

import com.agent.contract.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON Schema 校验：校验 required 字段存在与基本类型匹配。
 */
public final class ToolSchemaValidator {

    @SuppressWarnings("unchecked")
    public static List<String> validate(Map<String, Object> args, ToolSpec spec) {
        List<String> errors = new ArrayList<>();
        Object params = spec.parameters();
        if (!(params instanceof Map<?, ?> rawParams)) {
            return errors;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) rawParams;
        Object required = schema.get("required");
        if (required instanceof List<?> req) {
            for (Object r : req) {
                if (r != null && !args.containsKey(r.toString())) {
                    errors.add("缺少必填参数: " + r);
                }
            }
        }
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> e : properties.entrySet()) {
                String name = String.valueOf(e.getKey());
                Object value = args.get(name);
                if (value == null || !(e.getValue() instanceof Map<?, ?> def)) {
                    continue;
                }
                String type = String.valueOf(def.get("type"));
                if (!matchesType(value, type)) {
                    errors.add("参数 " + name + " 类型应为 " + type + "，实际: " + value.getClass().getSimpleName());
                }
            }
        }
        return errors;
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true;
        };
    }

    private ToolSchemaValidator() {
    }
}

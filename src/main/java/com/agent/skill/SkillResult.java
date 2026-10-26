package com.agent.skill;

import java.util.Map;

/**
 * 技能执行结果。
 */
public record SkillResult(boolean success, String output, Map<String, Object> data) {

    public SkillResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static SkillResult ok(String output) {
        return new SkillResult(true, output, Map.of());
    }

    public static SkillResult fail(String message) {
        return new SkillResult(false, message, Map.of());
    }
}

package com.agent.memory;

/**
 * 记忆反向纠偏：用户输入与既有记忆冲突时决定是否以用户输入为准。
 * 开关关闭时冲突保留原记忆；开启时覆盖（以用户为准）。
 */
public final class MemoryReverseOptimizer {

    private final boolean enabled;

    public MemoryReverseOptimizer(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * @param existingJson 既有记忆（可为 null 表示无冲突）
     * @param newJson      用户输入产生的新记忆
     * @return true 表示允许写入（覆盖）
     */
    public boolean shouldOverwrite(String existingJson, String newJson) {
        if (existingJson == null) {
            return true;
        }
        if (existingJson.equals(newJson)) {
            return true;
        }
        // 冲突：开关开启 → 用户为准；关闭 → 保留原记忆
        return enabled;
    }
}

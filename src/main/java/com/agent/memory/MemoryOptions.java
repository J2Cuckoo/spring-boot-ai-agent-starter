package com.agent.memory;

/**
 * 请求级记忆选项：由 {@code conversation.memory().tier(...)...} 生成，
 * 经 InterceptorContext 传递给 {@code MemoryInterceptor}，覆盖全局默认。
 */
public record MemoryOptions(Boolean enabled, MemoryTier tier, String namespace) {

    public static MemoryOptions DEFAULT = new MemoryOptions(null, null, null);
}

package com.agent.memory;

/**
 * 记忆分层：临时（3 天）/ 短期（7 天）/ 长期（正文存文件，Redis 存索引）。
 */
public enum MemoryTier {
    TEMPORARY,
    SHORT_TERM,
    LONG_TERM
}

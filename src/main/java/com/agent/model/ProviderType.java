package com.agent.model;

/**
 * 提供商类型：决定协议适配器与认证方式。
 */
public enum ProviderType {
    OPENAI,
    CLAUDE,
    GEMINI,
    OLLAMA,
    /** OpenAI 兼容的自定义端点。 */
    CUSTOM
}

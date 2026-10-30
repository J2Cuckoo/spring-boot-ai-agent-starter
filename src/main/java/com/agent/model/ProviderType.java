package com.agent.model;

/**
 * API 协议格式：决定协议适配器、端点路径与认证方式。
 * type 配置按协议格式填写，旧值（OPENAI/CLAUDE/GEMINI/OLLAMA/CUSTOM）与厂商名仍可解析。
 */
public enum ProviderType {

    /** OpenAI Chat Completions 格式（/chat/completions），绝大多数厂商兼容此格式。 */
    OPENAI_CHAT_COMPLETIONS,

    /** OpenAI Responses API 格式（/responses）。 */
    OPENAI_RESPONSES,

    /** Anthropic Messages 格式（/messages）。 */
    ANTHROPIC_MESSAGES,

    /** Google Gemini generateContent 格式。 */
    GEMINI,

    /** Ollama 原生 /api/chat（NDJSON 流）。 */
    OLLAMA
}

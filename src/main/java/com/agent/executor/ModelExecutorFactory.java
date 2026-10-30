package com.agent.executor;

import com.agent.model.ProviderType;
import com.agent.resilience.ResilienceManager;
import com.agent.tool.ToolExecutor;
import com.agent.transport.NettyHttpClient;

/**
 * 按 ProviderType 创建执行器（Factory）。执行器是轻量无状态对象，
 * 不做缓存：其持有的 NettyHttpClient 生命周期属于所属 Agent。
 */
public final class ModelExecutorFactory {

    private ModelExecutorFactory() {
    }

    public static AbstractModelExecutor create(ProviderType type, NettyHttpClient http,
                                               ResilienceManager resilience, ToolExecutor tools) {
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> new OpenAIExecutor(http, resilience, tools);
            case OPENAI_RESPONSES -> new OpenAIResponsesExecutor(http, resilience, tools);
            case ANTHROPIC_MESSAGES -> new ClaudeExecutor(http, resilience, tools);
            case GEMINI -> new GeminiExecutor(http, resilience, tools);
            case OLLAMA -> new OllamaExecutor(http, resilience, tools);
        };
    }
}

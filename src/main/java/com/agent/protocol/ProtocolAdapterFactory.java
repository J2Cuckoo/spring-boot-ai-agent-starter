package com.agent.protocol;

import com.agent.model.ProviderType;
import com.agent.protocol.claude.ClaudeAdapter;
import com.agent.protocol.gemini.GeminiAdapter;
import com.agent.protocol.ollama.OllamaAdapter;
import com.agent.protocol.openai.OpenAIAdapter;
import com.agent.protocol.openai.OpenAIResponsesAdapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 ProviderType 取协议适配器（Strategy + Factory）。CUSTOM 复用 OpenAI 兼容协议。
 */
public final class ProtocolAdapterFactory {

    private static final Map<ProviderType, ProtocolAdapter> REGISTRY = new ConcurrentHashMap<>();

    static {
        register(new OpenAIAdapter());
        register(new OpenAIResponsesAdapter());
        register(new ClaudeAdapter());
        register(new GeminiAdapter());
        register(new OllamaAdapter());
    }

    private ProtocolAdapterFactory() {
    }

    public static void register(ProtocolAdapter adapter) {
        REGISTRY.put(adapter.type(), adapter);
    }

    public static ProtocolAdapter get(ProviderType type) {
        ProtocolAdapter adapter = REGISTRY.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的 Provider 类型: " + type);
        }
        return adapter;
    }
}

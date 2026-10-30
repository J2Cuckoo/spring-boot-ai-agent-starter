package com.agent.executor;

import com.agent.model.ProviderType;
import com.agent.protocol.ProtocolAdapter;
import com.agent.protocol.ProtocolAdapterFactory;
import com.agent.resilience.ResilienceManager;
import com.agent.tool.ToolExecutor;
import com.agent.transport.NettyHttpClient;

/** CUSTOM 提供商执行器：走 OpenAI 兼容协议访问自建/本地网关。 */
public final class LocalModelExecutor extends AbstractModelExecutor {

    public LocalModelExecutor(NettyHttpClient http, ResilienceManager resilience, ToolExecutor tools) {
        super(http, resilience, tools);
    }

    @Override
    protected ProtocolAdapter adapter() {
        return ProtocolAdapterFactory.get(ProviderType.OPENAI_CHAT_COMPLETIONS);
    }
}

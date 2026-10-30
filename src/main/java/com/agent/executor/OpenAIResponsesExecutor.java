package com.agent.executor;

import com.agent.model.ProviderType;
import com.agent.protocol.ProtocolAdapter;
import com.agent.protocol.ProtocolAdapterFactory;
import com.agent.resilience.ResilienceManager;
import com.agent.tool.ToolExecutor;
import com.agent.transport.NettyHttpClient;

/** OpenAI Responses API 执行器。 */
public final class OpenAIResponsesExecutor extends AbstractModelExecutor {

    public OpenAIResponsesExecutor(NettyHttpClient http, ResilienceManager resilience, ToolExecutor tools) {
        super(http, resilience, tools);
    }

    @Override
    protected ProtocolAdapter adapter() {
        return ProtocolAdapterFactory.get(ProviderType.OPENAI_RESPONSES);
    }
}

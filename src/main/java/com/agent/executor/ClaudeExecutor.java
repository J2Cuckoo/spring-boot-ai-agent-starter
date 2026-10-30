package com.agent.executor;

import com.agent.model.ProviderType;
import com.agent.protocol.ProtocolAdapter;
import com.agent.protocol.ProtocolAdapterFactory;
import com.agent.resilience.ResilienceManager;
import com.agent.tool.ToolExecutor;
import com.agent.transport.NettyHttpClient;

/** Claude 执行器。 */
public final class ClaudeExecutor extends AbstractModelExecutor {

    public ClaudeExecutor(NettyHttpClient http, ResilienceManager resilience, ToolExecutor tools) {
        super(http, resilience, tools);
    }

    @Override
    protected ProtocolAdapter adapter() {
        return ProtocolAdapterFactory.get(ProviderType.ANTHROPIC_MESSAGES);
    }
}

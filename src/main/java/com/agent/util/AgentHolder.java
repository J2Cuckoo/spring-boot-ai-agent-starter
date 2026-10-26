package com.agent.util;

import com.agent.Agent;

/**
 * Agent 的可延迟引用。门禁/工作流等拦截器需要在装配期引用 Agent，
 * 而 Agent 本身又装配拦截器，此 Holder 打破循环：先创建 Holder 注入拦截器，Agent 构建完成后回填。
 */
public final class AgentHolder {

    private volatile Agent agent;

    public void set(Agent agent) {
        this.agent = agent;
    }

    /** 尚未回填时抛 {@link IllegalStateException}。 */
    public Agent get() {
        Agent a = agent;
        if (a == null) {
            throw new IllegalStateException("Agent 尚未初始化（AgentHolder 未回填）");
        }
        return a;
    }

    public boolean isAvailable() {
        return agent != null;
    }
}

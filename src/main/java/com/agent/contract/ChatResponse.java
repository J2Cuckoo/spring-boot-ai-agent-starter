package com.agent.contract;

import java.util.List;

/**
 * 统一响应对象（非流式聚合视图）。
 */
public record ChatResponse(String content, List<ToolCall> toolCalls, Usage usage,
                           String finishReason, boolean partial) {

    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.zero() : usage;
    }
}

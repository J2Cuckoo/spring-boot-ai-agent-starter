package com.agent.output;

import com.agent.contract.ToolCall;
import com.agent.contract.Usage;

import java.util.List;

/**
 * {@code .writeFile} 写入的响应信封结构。
 */
public record ResponseEnvelope(String format, String model, String provider, Object content,
                               List<ToolCall> toolCalls, Usage usage,
                               String finishReason, String timestamp) {

    public ResponseEnvelope {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.zero() : usage;
    }
}

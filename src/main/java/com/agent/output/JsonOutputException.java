package com.agent.output;

import com.agent.contract.AgentException;
import com.agent.contract.ErrorCodes;

/**
 * {@code .writeJson} 反序列化失败异常（含原始输出便于排查）。
 */
public final class JsonOutputException extends AgentException {

    private final String rawOutput;

    public JsonOutputException(String message, String rawOutput) {
        super(ErrorCodes.JSON_OUTPUT_ERROR, "JSON 输出解析失败: " + message, true);
        this.rawOutput = rawOutput;
    }

    public String rawOutput() {
        return rawOutput;
    }
}

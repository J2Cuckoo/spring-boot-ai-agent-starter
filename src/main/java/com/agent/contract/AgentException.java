package com.agent.contract;

/**
 * SDK 统一运行时异常，携带错误码与可重试标记。
 */
public class AgentException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public AgentException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public AgentException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}

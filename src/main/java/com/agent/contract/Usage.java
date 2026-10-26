package com.agent.contract;

/**
 * Token 用量。
 */
public record Usage(int promptTokens, int completionTokens, int totalTokens) {

    public static Usage zero() {
        return new Usage(0, 0, 0);
    }

    public Usage plus(Usage other) {
        return new Usage(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens);
    }
}

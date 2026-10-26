package com.agent.contract;

import java.util.List;

/**
 * 统一消息：system / user / assistant / tool。
 */
public record Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public Message {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null);
    }

    public static Message assistantWithTools(String content, List<ToolCall> calls) {
        return new Message(Role.ASSISTANT, content, null, calls);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message(Role.TOOL, content, toolCallId, null);
    }
}

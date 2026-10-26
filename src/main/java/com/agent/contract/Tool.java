package com.agent.contract;

import java.util.Map;

/**
 * 工具 SPI（声明式注册）：实现本接口并注册为 Spring Bean（或经 {@code Agent.registerTool(Tool)}），
 * SDK 自动收集并注册执行逻辑，无需在 {@code @PostConstruct} 里手动注册。
 *
 * <p>调用时用 {@code conversation.tool(tool)}（或 {@code conversation.tool(tool.spec())}）
 * 声明本次对话可用的工具，模型返回 tool_call 后 SDK 自动执行 {@link #execute(Map)} 并回填结果。</p>
 */
public interface Tool {

    /** 工具名（唯一，模型据此发起调用）。 */
    String name();

    /** 工具描述（供模型理解用途）。 */
    String description();

    /** 参数 JSON Schema（含 type/properties/required）。 */
    Map<String, Object> parameters();

    /** 执行逻辑：入参为模型返回的参数，返回结果字符串回填给模型。 */
    String execute(Map<String, Object> arguments);

    /** 转工具定义，供 {@code conversation.tool(...)} 声明。 */
    default ToolSpec spec() {
        return ToolSpec.of(name(), description(), parameters());
    }
}

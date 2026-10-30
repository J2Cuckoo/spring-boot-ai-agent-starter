package com.agent.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工作流执行：{@code graph.execute().input(...).output()}。
 * input() 提供用户输入，output() 阻塞执行到完成并返回 end 声明的输出键的值。
 */
public final class GraphExecution {

    private final AgentGraph graph;
    private final Map<String, Object> inputs = new LinkedHashMap<>();

    GraphExecution(AgentGraph graph) {
        this.graph = graph;
    }

    /** 按输入键提供输入（定义时 .start(key) 声明的键）。 */
    public GraphExecution input(String key, Object value) {
        inputs.put(key, value);
        return this;
    }

    /** 便捷：单输入工作流直接给值，绑定到 start 声明的输入键。 */
    public GraphExecution input(Object value) {
        inputs.put(graph.inputKey(), value);
        return this;
    }

    /** 提供全部输入。 */
    public GraphExecution inputs(Map<String, Object> values) {
        inputs.putAll(values);
        return this;
    }

    /** 启动执行并阻塞到完成，返回 end 声明的输出键的值。 */
    @SuppressWarnings("unchecked")
    public <T> T output() {
        if (!inputs.containsKey(graph.inputKey())) {
            throw new IllegalStateException("缺少输入 " + graph.inputKey()
                    + "（定义时 .start(\"" + graph.inputKey() + "\") 声明，执行时用 .input(...) 提供）");
        }
        return (T) graph.runOnce(inputs);
    }

    /** 指定型别的输出（等价 {@link #output()}，便于链式书写）。 */
    public <T> T output(Class<T> type) {
        return output();
    }
}

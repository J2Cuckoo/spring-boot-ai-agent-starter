package com.agent.workflow.steps;

import com.agent.contract.ToolCall;
import com.agent.tool.ToolExecutor;
import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Function;

/**
 * 工具执行节点：按名执行已注册工具，结果写入 {@code output.<name>}。
 */
public final class ToolExecutionStep implements WorkflowStep {

    private final ToolExecutor executor;
    private final String stepName;
    private final String toolName;
    private final Function<WorkflowContext, Map<String, Object>> argsFn;
    private final String outputKey;

    public ToolExecutionStep(ToolExecutor executor, String stepName, String toolName,
                             Function<WorkflowContext, Map<String, Object>> argsFn, String outputKey) {
        this.executor = executor;
        this.stepName = stepName;
        this.toolName = toolName;
        this.argsFn = argsFn;
        this.outputKey = outputKey;
    }

    @Override
    public String name() {
        return stepName;
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        return Mono.fromCallable(() -> executor.execute(new ToolCall(
                        "wf-" + stepName, toolName, argsFn.apply(ctx))))
                .subscribeOn(com.agent.util.SdkSchedulers.io())
                .flatMapMany(result -> {
                    ctx.put(outputKey == null ? "output." + stepName : outputKey, result);
                    ctx.addMessage("[" + stepName + ":tool] " + result);
                    return Flux.<WorkflowEvent>empty();
                });
    }
}

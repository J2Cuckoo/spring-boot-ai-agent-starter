package com.agent.workflow.steps;

import com.agent.contract.ChatEvent;
import com.agent.workflow.AgentGraph;
import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 子工作流节点：在父工作流中引用另一个 {@link AgentGraph}。
 * 子图执行时创建全新 WorkflowContext——多个工作流之间状态完全隔离，
 * 仅按声明交换一对输入/输出。
 */
public final class SubWorkflowStep implements WorkflowStep {

    private final AgentGraph sub;
    private final String parentInKey;
    private final String parentOutKey;

    public SubWorkflowStep(AgentGraph sub, String parentInKey, String parentOutKey) {
        this.sub = sub;
        this.parentInKey = parentInKey;
        this.parentOutKey = parentOutKey;
    }

    @Override
    public String name() {
        return "sub:" + sub.name();
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext parentCtx) {
        return Mono.fromCallable(() -> {
                    Object in = parentCtx.get(parentInKey);
                    Object out = sub.runOnce(Map.of(sub.inputKey(), in == null ? "" : in));
                    parentCtx.put(parentOutKey, out);
                    return (WorkflowEvent) new WorkflowEvent.StepCompleted(name(), out);
                })
                .subscribeOn(com.agent.util.SdkSchedulers.io())
                .flux();
    }
}

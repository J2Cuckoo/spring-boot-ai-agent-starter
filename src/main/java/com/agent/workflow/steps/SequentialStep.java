package com.agent.workflow.steps;

import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 顺序执行：串行推进，前序输出写入 {@code output.<step>} 供后续节点读取。
 * 包装层统一发 StepStarted / StepCompleted / StepFailed，节点自身只发领域事件。
 */
public final class SequentialStep implements WorkflowStep {

    private final List<WorkflowStep> steps;

    public SequentialStep(List<WorkflowStep> steps) {
        this.steps = List.copyOf(steps);
    }

    @Override
    public String name() {
        return "sequential(" + steps.size() + ")";
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        List<Flux<WorkflowEvent>> fluxes = new ArrayList<>(steps.size());
        for (WorkflowStep step : steps) {
            fluxes.add(wrap(step, ctx));
        }
        return Flux.concat(fluxes);
    }

    /** 单节点包装：Started + 节点领域事件 + Completed（输出取 ctx）。 */
    public static Flux<WorkflowEvent> wrap(WorkflowStep step, WorkflowContext ctx) {
        return Flux.defer(() -> {
            if (ctx.isCancelled()) {
                return Flux.just(new WorkflowEvent.StepFailed(step.name(),
                        new IllegalStateException("工作流已取消")));
            }
            return Flux.concat(
                    Flux.just(new WorkflowEvent.StepStarted(step.name())),
                    step.execute(ctx),
                    Mono.fromCallable(() -> new WorkflowEvent.StepCompleted(
                            step.name(), ctx.get("output." + step.name()))));
        }).onErrorResume(e -> Flux.just(new WorkflowEvent.StepFailed(step.name(), e)));
    }
}

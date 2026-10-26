package com.agent.workflow.steps;

import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Predicate;

/**
 * 条件判断：谓词决定走 then 还是 else 分支。
 */
public final class ConditionStep implements WorkflowStep {

    private final Predicate<WorkflowContext> predicate;
    private final WorkflowStep thenStep;
    private final WorkflowStep elseStep;

    public ConditionStep(Predicate<WorkflowContext> predicate, WorkflowStep thenStep, WorkflowStep elseStep) {
        this.predicate = predicate;
        this.thenStep = thenStep;
        this.elseStep = elseStep;
    }

    @Override
    public String name() {
        return "when(" + (thenStep == null ? "-" : thenStep.name()) + "/" + (elseStep == null ? "-" : elseStep.name()) + ")";
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        return Mono.fromCallable(() -> predicate.test(ctx))
                .flatMapMany(match -> {
                    WorkflowStep next = match ? thenStep : elseStep;
                    return next == null ? Flux.empty() : SequentialStep.wrap(next, ctx);
                });
    }
}

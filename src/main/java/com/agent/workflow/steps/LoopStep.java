package com.agent.workflow.steps;

import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import reactor.core.publisher.Flux;

import java.util.function.Predicate;

/**
 * 循环：条件满足前重复执行 body，maxIterations 兜底（计数走 WorkflowContext）。
 */
public final class LoopStep implements WorkflowStep {

    private final Predicate<WorkflowContext> condition;
    private final WorkflowStep body;
    private final int maxIterations;

    public LoopStep(Predicate<WorkflowContext> condition, WorkflowStep body, int maxIterations) {
        this.condition = condition;
        this.body = body;
        this.maxIterations = Math.max(1, maxIterations);
    }

    @Override
    public String name() {
        return "loop(" + body.name() + ")";
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        String counterKey = "loop." + name();
        return loop(ctx, counterKey, 0);
    }

    private Flux<WorkflowEvent> loop(WorkflowContext ctx, String counterKey, int i) {
        return Flux.defer(() -> {
            if (ctx.isCancelled()) {
                return Flux.just(new WorkflowEvent.StepFailed(name(), new IllegalStateException("工作流已取消")));
            }
            if (i >= maxIterations || !condition.test(ctx)) {
                return Flux.<WorkflowEvent>empty();
            }
            int round = ctx.bump(counterKey);
            return Flux.concat(
                    Flux.just(new WorkflowEvent.Iteration(name(), round, "第 " + round + " 轮")),
                    SequentialStep.wrap(body, ctx),
                    loop(ctx, counterKey, i + 1));
        });
    }
}

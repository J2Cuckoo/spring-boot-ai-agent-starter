package com.agent.workflow.steps;

import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 多节点并发（Fan-out/Fan-in）：全部完成后汇聚，各分支输出写入
 * {@code parallel.<step>}，聚合列表写入 {@code output.parallel}。
 */
public final class ParallelStep implements WorkflowStep {

    private final List<WorkflowStep> steps;

    public ParallelStep(List<WorkflowStep> steps) {
        this.steps = List.copyOf(steps);
    }

    @Override
    public String name() {
        return "parallel(" + steps.size() + ")";
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        List<Flux<WorkflowEvent>> branches = new ArrayList<>(steps.size());
        for (WorkflowStep step : steps) {
            branches.add(SequentialStep.wrap(step, ctx)
                    .doOnNext(e -> {
                        if (e instanceof WorkflowEvent.StepCompleted sc) {
                            ctx.put("parallel." + sc.step(), sc.output());
                        }
                    }));
        }
        return Flux.merge(branches)
                .concatWith(reactor.core.publisher.Mono.fromCallable(() -> {
                    List<Object> outputs = new ArrayList<>(steps.size());
                    for (WorkflowStep step : steps) {
                        outputs.add(ctx.get("parallel." + step.name()));
                    }
                    ctx.put("output.parallel", outputs);
                    return (WorkflowEvent) new WorkflowEvent.StepCompleted(name(), outputs);
                }));
    }
}

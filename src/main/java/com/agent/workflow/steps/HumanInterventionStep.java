package com.agent.workflow.steps;

import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStateMachine;
import com.agent.workflow.WorkflowStep;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 人类干预：进入 WAITING_HUMAN，等待人工输入或审批；批准走 onApprove，拒绝走 onReject。
 */
public final class HumanInterventionStep implements WorkflowStep {

    private final String prompt;
    private final WorkflowStep onApprove;
    private final WorkflowStep onReject;

    public HumanInterventionStep(String prompt, WorkflowStep onApprove, WorkflowStep onReject) {
        this.prompt = prompt;
        this.onApprove = onApprove;
        this.onReject = onReject;
    }

    @Override
    public String name() {
        return "awaitHuman";
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        return Flux.concat(
                Mono.fromCallable(() -> {
                    WorkflowStateMachine sm = ctx.stateMachine();
                    if (sm != null) {
                        sm.transition(WorkflowStateMachine.State.WAITING_HUMAN);
                    }
                    return (WorkflowEvent) new WorkflowEvent.HumanRequested(name(), prompt);
                }),
                Mono.fromFuture(ctx.awaitHumanSignal())
                        .subscribeOn(com.agent.util.SdkSchedulers.io())
                        .flatMapMany(signal -> {
                            WorkflowStateMachine sm = ctx.stateMachine();
                            if (sm != null) {
                                sm.transition(WorkflowStateMachine.State.RUNNING);
                            }
                            ctx.put("human.payload", signal.payload());
                            WorkflowStep next = signal.approved() ? onApprove : onReject;
                            return next == null ? Flux.empty() : SequentialStep.wrap(next, ctx);
                        }));
    }
}

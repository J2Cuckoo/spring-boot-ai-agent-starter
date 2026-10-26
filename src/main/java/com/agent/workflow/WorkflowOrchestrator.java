package com.agent.workflow;

import reactor.core.publisher.Flux;

/**
 * 工作流执行引擎：遍历 DAG（步骤递归组合），驱动状态机并转发事件。
 */
public final class WorkflowOrchestrator {

    /** 执行单个入口步骤（通常为 WorkflowBuilder.build() 产出的组合步骤）。 */
    public Flux<WorkflowEvent> run(WorkflowStep step, WorkflowContext ctx) {
        WorkflowStateMachine sm = new WorkflowStateMachine();
        ctx.attach(sm);
        return step.execute(ctx)
                .doOnSubscribe(s -> sm.transition(WorkflowStateMachine.State.RUNNING))
                .doOnComplete(() -> safeTransition(sm, WorkflowStateMachine.State.SUCCEEDED))
                .doOnError(e -> safeTransition(sm, WorkflowStateMachine.State.FAILED))
                .onErrorMap(e -> e instanceof RuntimeException r ? r
                        : new IllegalStateException("工作流失败: " + e.getMessage(), e));
    }

    /** 阻塞执行至完成，返回最后输出。 */
    public void runBlocking(WorkflowStep step, WorkflowContext ctx) {
        run(step, ctx).blockLast();
    }

    private void safeTransition(WorkflowStateMachine sm, WorkflowStateMachine.State to) {
        try {
            sm.transitionIfIn(java.util.Set.of(WorkflowStateMachine.State.RUNNING,
                            WorkflowStateMachine.State.WAITING_HUMAN),
                    to);
        } catch (Exception ignored) {
            // 步骤内部已转移到终态（如 CANCELLED），保持不动
        }
    }
}

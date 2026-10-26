package com.agent.workflow;

import org.junit.jupiter.api.Test;
import com.agent.workflow.steps.ConditionStep;
import com.agent.workflow.steps.HumanInterventionStep;
import com.agent.workflow.steps.LoopStep;
import com.agent.workflow.steps.SequentialStep;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowOrchestratorTest {

    /** 回显步骤：写 output.<name>。 */
    private static WorkflowStep echo(String name, String value) {
        return new WorkflowStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
                ctx.put("output." + name, value);
                ctx.addMessage(name + ":" + value);
                return Flux.empty();
            }
        };
    }

    @Test
    void sequentialChainRunsInOrder() {
        SequentialStep step = new SequentialStep(List.of(echo("a", "1"), echo("b", "2")));
        WorkflowContext ctx = new WorkflowContext();
        List<WorkflowEvent> events = new WorkflowOrchestrator().run(step, ctx)
                .collectList()
                .block(java.time.Duration.ofSeconds(5));
        assertThat(events).hasSize(4);   // started/completed × 2
        assertThat(events.get(0)).isInstanceOf(WorkflowEvent.StepStarted.class);
        assertThat(ctx.get("output.a")).isEqualTo("1");
        assertThat(ctx.get("output.b")).isEqualTo("2");
        assertThat(ctx.messages()).containsExactly("a:1", "b:2");
        assertThat(ctx.stateMachine().state()).isEqualTo(WorkflowStateMachine.State.SUCCEEDED);
    }

    @Test
    void conditionSelectsBranch() {
        ConditionStep yes = new ConditionStep(ctx -> true, echo("then", "T"), echo("else", "E"));
        ConditionStep no = new ConditionStep(ctx -> false, echo("then", "T"), echo("else", "E"));
        WorkflowContext ctx = new WorkflowContext();
        new WorkflowOrchestrator().run(yes, ctx).blockLast(java.time.Duration.ofSeconds(5));
        new WorkflowOrchestrator().run(no, ctx).blockLast(java.time.Duration.ofSeconds(5));
        assertThat(ctx.get("output.then")).isEqualTo("T");
        assertThat(ctx.get("output.else")).isEqualTo("E");
    }

    @Test
    void loopRunsUntilConditionBreaks() {
        // body 每轮 bump("rounds")，谓词读到 3 停
        WorkflowStep body = new WorkflowStep() {
            @Override
            public String name() {
                return "body";
            }

            @Override
            public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
                ctx.bump("rounds");
                return Flux.empty();
            }
        };
        LoopStep loop = new LoopStep(ctx -> ctx.current("rounds") < 3, body, 10);
        WorkflowContext ctx = new WorkflowContext();
        List<WorkflowEvent> events = new WorkflowOrchestrator().run(loop, ctx)
                .collectList()
                .block(java.time.Duration.ofSeconds(5));
        long iterations = events.stream().filter(e -> e instanceof WorkflowEvent.Iteration).count();
        assertThat(iterations).isEqualTo(3);
        assertThat(ctx.current("rounds")).isEqualTo(3);
    }

    @Test
    void humanInterventionWaitsForSignalThenApproves() throws Exception {
        HumanInterventionStep step = new HumanInterventionStep("是否继续？", echo("approved", "Y"), echo("rejected", "N"));
        WorkflowContext ctx = new WorkflowContext();
        List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch finished = new CountDownLatch(1);
        new WorkflowOrchestrator().run(step, ctx)
                .doOnNext(events::add)
                .doOnComplete(finished::countDown)
                .subscribe();

        // 等到 WAITING_HUMAN
        for (int i = 0; i < 100 && ctx.stateMachine() == null; i++) {
            Thread.sleep(20);
        }
        for (int i = 0; i < 100 && ctx.stateMachine().state() != WorkflowStateMachine.State.WAITING_HUMAN; i++) {
            Thread.sleep(20);
        }
        assertThat(ctx.stateMachine().state()).isEqualTo(WorkflowStateMachine.State.WAITING_HUMAN);

        assertThat(ctx.sendHumanSignal(true, "继续")).isTrue();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ctx.get("output.approved")).isEqualTo("Y");
        assertThat(ctx.stateMachine().state()).isEqualTo(WorkflowStateMachine.State.SUCCEEDED);
        assertThat(events).anyMatch(e -> e instanceof WorkflowEvent.HumanRequested);
    }

    @Test
    void failedStepMarksWorkflowFailed() {
        WorkflowStep failing = new WorkflowStep() {
            @Override
            public String name() {
                return "bad";
            }

            @Override
            public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
                return Flux.error(new IllegalStateException("节点失败"));
            }
        };
        WorkflowContext ctx = new WorkflowContext();
        try {
            new WorkflowOrchestrator().run(failing, ctx).blockLast(java.time.Duration.ofSeconds(5));
        } catch (Exception expected) {
            assertThat(expected).hasMessageContaining("节点失败");
        }
        assertThat(ctx.stateMachine().state()).isEqualTo(WorkflowStateMachine.State.FAILED);
    }
}

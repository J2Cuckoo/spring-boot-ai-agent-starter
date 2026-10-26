package com.agent.workflow;

import org.junit.jupiter.api.Test;

import com.agent.workflow.WorkflowStateMachine.State;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowStateMachineTest {

    @Test
    void legalLifecycle() {
        WorkflowStateMachine sm = new WorkflowStateMachine();
        sm.transition(State.RUNNING);
        sm.transition(State.WAITING_HUMAN);
        sm.transition(State.RUNNING);          // 人工信号后恢复
        sm.transition(State.SUCCEEDED);
        assertThat(sm.state()).isEqualTo(State.SUCCEEDED);
        assertThat(sm.isTerminal()).isTrue();
    }

    @Test
    void pendingCannotJumpToSuccess() {
        WorkflowStateMachine sm = new WorkflowStateMachine();
        assertThatThrownBy(() -> sm.transition(State.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法状态转移");
    }

    @Test
    void terminalStateIsFrozen() {
        WorkflowStateMachine sm = new WorkflowStateMachine();
        sm.transition(State.RUNNING);
        sm.transition(State.FAILED);
        assertThatThrownBy(() -> sm.transition(State.RUNNING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelFromWaitingHuman() {
        WorkflowStateMachine sm = new WorkflowStateMachine();
        sm.transition(State.RUNNING);
        sm.transition(State.WAITING_HUMAN);
        sm.transition(State.CANCELLED);
        assertThat(sm.state()).isEqualTo(State.CANCELLED);
    }
}

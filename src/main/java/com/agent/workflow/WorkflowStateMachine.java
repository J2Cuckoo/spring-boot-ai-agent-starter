package com.agent.workflow;

import java.util.Map;
import java.util.Set;

/**
 * 工作流状态机：约束合法转移。
 * PENDING→RUNNING→{WAITING_HUMAN|SUCCEEDED|FAILED|CANCELLED}；WAITING_HUMAN 收到信号回 RUNNING。
 */
public final class WorkflowStateMachine {

    public enum State { PENDING, RUNNING, WAITING_HUMAN, SUCCEEDED, FAILED, CANCELLED }

    private static final Map<State, Set<State>> ALLOWED = Map.of(
            State.PENDING, Set.of(State.RUNNING, State.CANCELLED),
            State.RUNNING, Set.of(State.WAITING_HUMAN, State.SUCCEEDED, State.FAILED, State.CANCELLED),
            State.WAITING_HUMAN, Set.of(State.RUNNING, State.FAILED, State.CANCELLED),
            State.SUCCEEDED, Set.of(),
            State.FAILED, Set.of(),
            State.CANCELLED, Set.of());

    private volatile State state = State.PENDING;

    public synchronized void transition(State to) {
        if (!ALLOWED.get(state).contains(to)) {
            throw new IllegalStateException("非法状态转移: " + state + " -> " + to);
        }
        state = to;
    }

    /** 仅当当前处于期望状态之一时转移；用于可选动作（避免重复终态转移抛错）。 */
    public synchronized boolean transitionIfIn(Set<State> expected, State to) {
        if (expected.contains(state) && ALLOWED.get(state).contains(to)) {
            state = to;
            return true;
        }
        return false;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean isTerminal() {
        return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED;
    }
}

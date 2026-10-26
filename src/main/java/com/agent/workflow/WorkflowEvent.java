package com.agent.workflow;

import com.agent.contract.ChatEvent;

/**
 * 工作流事件（sealed）：节点生命周期、人工请求、迭代进度与模型事件透传。
 */
public sealed interface WorkflowEvent {

    record StepStarted(String step) implements WorkflowEvent {
    }

    record StepCompleted(String step, Object output) implements WorkflowEvent {
    }

    record StepFailed(String step, Throwable error) implements WorkflowEvent {
    }

    record HumanRequested(String step, String prompt) implements WorkflowEvent {
    }

    record Iteration(String step, int round, String summary) implements WorkflowEvent {
    }

    /** 模型推理步骤的 ChatEvent 透传（StreamingWorkflowOrchestrator 消费）。 */
    record ModelEvent(String step, ChatEvent event) implements WorkflowEvent {
    }
}

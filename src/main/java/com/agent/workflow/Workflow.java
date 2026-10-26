package com.agent.workflow;

import reactor.core.publisher.Flux;

/**
 * 构建产物：名称 + 入口步骤。
 */
public record Workflow(String name, WorkflowStep entry) {

    public Flux<WorkflowEvent> run(WorkflowContext ctx) {
        return new WorkflowOrchestrator().run(entry, ctx);
    }

    public void runBlocking(WorkflowContext ctx) {
        new WorkflowOrchestrator().runBlocking(entry, ctx);
    }
}

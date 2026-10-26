package com.agent.workflow;

import reactor.core.publisher.Flux;

/**
 * 工作流节点契约（稳定接口）：输入处理输出，通过 ctx 交换变量与信号。
 */
public interface WorkflowStep {

    String name();

    Flux<WorkflowEvent> execute(WorkflowContext ctx);
}

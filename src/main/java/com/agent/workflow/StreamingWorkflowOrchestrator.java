package com.agent.workflow;

import com.agent.contract.ChatEvent;
import reactor.core.publisher.Flux;

/**
 * 流式执行引擎：把 ModelInferenceStep 透传的 ChatEvent 抽取为独立事件流，
 * 不阻塞等待整段完成。
 */
public final class StreamingWorkflowOrchestrator {

    public Flux<ChatEvent> stream(WorkflowStep step, WorkflowContext ctx) {
        return new WorkflowOrchestrator().run(step, ctx)
                .concatMap(e -> e instanceof WorkflowEvent.ModelEvent m
                        ? Flux.just(m.event())
                        : Flux.<ChatEvent>empty());
    }
}

package com.agent.workflow.steps;

import com.agent.Agent;
import com.agent.contract.ChatEvent;
import com.agent.model.ModelRef;
import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 模型推理节点：内部走完整 Agent 链路（记忆、RAG、skill 拦截器同样生效），
 * token 以 ModelEvent 透传，聚合结果写入 {@code output.<name>}。
 * <p>模型可由静态字符串/ModelRef 指定，也可用 {@code Function<WorkflowContext,String>}
 * 在运行时根据上下文动态切换（返回 null 用默认模型）。</p>
 */
public final class ModelInferenceStep implements WorkflowStep {

    private final Agent agent;
    private final String stepName;
    private final Function<WorkflowContext, String> promptFn;
    private final Function<WorkflowContext, String> modelResolver;
    private final String outputKey;

    /** 静态模型（provider:model，null 用默认模型）。 */
    public ModelInferenceStep(Agent agent, String stepName,
                              Function<WorkflowContext, String> promptFn, String model, String outputKey) {
        this(agent, stepName, promptFn, model == null ? ctx -> null : ctx -> model, outputKey);
    }

    /** 静态模型（ModelRef 配置对象，null 用默认模型）。 */
    public ModelInferenceStep(Agent agent, String stepName,
                              Function<WorkflowContext, String> promptFn, ModelRef model, String outputKey) {
        this(agent, stepName, promptFn, model == null ? ctx -> null : ctx -> model.ref(), outputKey);
    }

    /** 动态模型：运行时根据上下文返回 provider:model。 */
    public ModelInferenceStep(Agent agent, String stepName,
                              Function<WorkflowContext, String> promptFn,
                              Function<WorkflowContext, String> modelResolver, String outputKey) {
        this.agent = agent;
        this.stepName = stepName;
        this.promptFn = promptFn;
        this.modelResolver = modelResolver;
        this.outputKey = outputKey;
    }

    @Override
    public String name() {
        return stepName;
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        StringBuilder content = new StringBuilder();
        com.agent.output.Conversation conversation = agent.chat(promptFn.apply(ctx));
        String model = modelResolver.apply(ctx);
        if (model != null && !model.isBlank()) {
            conversation.using(model);
        }
        return conversation.writeStream()
                .doOnNext(e -> {
                    if (e instanceof ChatEvent.TokenEvent t) {
                        content.append(t.delta() == null ? "" : t.delta());
                    }
                })
                .map(e -> (WorkflowEvent) new WorkflowEvent.ModelEvent(stepName, e))
                .doOnComplete(() -> {
                    String text = content.toString();
                    ctx.put(outputKey == null ? "output." + stepName : outputKey, text);
                    ctx.addMessage("[" + stepName + "] " + text);
                });
    }
}

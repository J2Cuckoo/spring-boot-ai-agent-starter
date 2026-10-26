package com.agent.workflow.steps;

import com.agent.skill.SkillManager;
import com.agent.skill.SkillProgress;
import com.agent.skill.SkillResult;
import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Function;

/**
 * 技能节点：调用 SkillManager 执行技能，结果写入 {@code output.<name>}。
 */
public final class SkillStep implements WorkflowStep {

    private final SkillManager manager;
    private final String stepName;
    private final String skillId;
    private final Function<WorkflowContext, Map<String, Object>> argsFn;
    private final String outputKey;

    public SkillStep(SkillManager manager, String stepName, String skillId,
                     Function<WorkflowContext, Map<String, Object>> argsFn, String outputKey) {
        this.manager = manager;
        this.stepName = stepName;
        this.skillId = skillId;
        this.argsFn = argsFn;
        this.outputKey = outputKey;
    }

    @Override
    public String name() {
        return stepName;
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        return Mono.fromCallable(() -> manager.execute(skillId, argsFn.apply(ctx),
                        (SkillProgress) (stage, percent) -> {
                        }))
                .subscribeOn(com.agent.util.SdkSchedulers.io())
                .flatMapMany(result -> {
                    SkillResult r = result == null ? com.agent.skill.SkillResult.fail("无结果") : result;
                    ctx.put(outputKey == null ? "output." + stepName : outputKey, r.output());
                    ctx.addMessage("[" + stepName + ":skill] " + r.output());
                    return Flux.<WorkflowEvent>empty();
                });
    }
}

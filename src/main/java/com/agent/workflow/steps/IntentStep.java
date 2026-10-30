package com.agent.workflow.steps;

import com.agent.Agent;
import com.agent.skill.Skill;
import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import com.agent.serialize.JacksonConfig;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 意图解析节点：按内置执行流程「用户输入 → 系统解析意图 → 匹配/加载 Skill」，
 * 用 LLM 从已注册技能清单中选出最匹配的一项，结果写入：
 * <ul>
 *   <li>{@code intent.skill}：选中的技能 id（无匹配为空串）</li>
 *   <li>{@code intent.args}：为该技能生成的调用参数（Map）</li>
 * </ul>
 * 后续节点可用 {@code .skill("run", ctx -> ctx.get("intent.args"))} 加载执行技能。
 */
public final class IntentStep implements WorkflowStep {

    /** 意图解析结果键：技能 id。 */
    public static final String SKILL_KEY = "intent.skill";
    /** 意图解析结果键：调用参数。 */
    public static final String ARGS_KEY = "intent.args";

    private static final String PROMPT_TEMPLATE = """
            从下列技能中选出最匹配用户输入的一项，并为该技能生成调用参数。
            只输出 JSON：{"skill": "技能id或空串", "args": {参数对象}}
            无任何匹配时 skill 为空串、args 为空对象。
            技能清单：
            %s

            用户输入：
            %s""";

    private final Agent agent;
    private final String model;      // provider:model，null 用默认模型
    private final String inputKey;   // 用户输入在上下文中的键

    public IntentStep(Agent agent, String model, String inputKey) {
        this.agent = agent;
        this.model = model;
        this.inputKey = inputKey;
    }

    @Override
    public String name() {
        return "intent";
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        return Mono.fromCallable(() -> {
                    String userInput = String.valueOf(ctx.get(inputKey));
                    List<Skill> skills = agent.config().skillManager() == null
                            ? List.of() : agent.config().skillManager().all();
                    String catalog = skills.isEmpty() ? "（无已注册技能）" : skills.stream()
                            .map(s -> "- " + s.id() + "：" + s.name() + "——" + s.description())
                            .collect(Collectors.joining("\n"));
                    var conversation = agent.chat(PROMPT_TEMPLATE.formatted(catalog, userInput));
                    if (model != null && !model.isBlank()) {
                        conversation.using(model);
                    }
                    String raw = conversation.write();
                    ctx.put(SKILL_KEY, "");
                    ctx.put(ARGS_KEY, Map.of());
                    try {
                        JsonNode node = JacksonConfig.mapper()
                                .readTree(com.agent.output.Conversation.stripCodeFence(raw));
                        ctx.put(SKILL_KEY, node.path("skill").asText(""));
                        ctx.put(ARGS_KEY, JacksonConfig.mapper()
                                .convertValue(node.path("args"), Map.class));
                    } catch (Exception e) {
                        // 解析失败保留空意图，不中断流程
                        org.slf4j.LoggerFactory.getLogger(IntentStep.class)
                                .warn("意图解析失败，保留空意图：raw={}，原因={}", raw, e.getMessage());
                    }
                    return (WorkflowEvent) new WorkflowEvent.StepCompleted(name(), ctx.get(SKILL_KEY));
                })
                .subscribeOn(com.agent.util.SdkSchedulers.io())
                .flux();
    }
}

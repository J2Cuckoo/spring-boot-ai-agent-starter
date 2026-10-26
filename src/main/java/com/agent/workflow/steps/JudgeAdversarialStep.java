package com.agent.workflow.steps;

import com.agent.Agent;
import com.agent.serialize.JacksonConfig;
import com.agent.workflow.WorkflowContext;
import com.agent.workflow.WorkflowEvent;
import com.agent.workflow.WorkflowStep;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 裁判对抗：裁判模型指定目标，两方执行模型从不同角度产出，裁判对比判定优缺点
 * 并同步给两方，双方整合后重新产出，循环到任意一方达标。
 */
public final class JudgeAdversarialStep implements WorkflowStep {

    private static final String TARGET_PROMPT = """
            你是裁判。请指定本次对抗的目标结果与验收要求，只输出目标描述。""";

    private static final String SIDE_PROMPT = """
            你的任务目标：\n%s\n\n你从「%s」角度提出方案。\
            如有前轮裁判反馈，请整合改进：\n%s\n\n只输出你的方案。""";

    private static final String JUDGE_PROMPT = """
            你是裁判。对比以下两方结果，只输出 JSON：\
            {"passA": true/false, "passB": true/false, "prosA": "...", "consA": "...", \
            "prosB": "...", "consB": "..."}\n\n目标：\n%s\n\n甲方：\n%s\n\n乙方：\n%s""";

    private final Agent agent;
    private final String judge;
    private final String sideA;
    private final String sideB;
    private final int maxIterations;
    private final String outputKey;

    public JudgeAdversarialStep(Agent agent, String judge, String sideA, String sideB,
                                int maxIterations, String outputKey) {
        this.agent = agent;
        this.judge = judge;
        this.sideA = sideA;
        this.sideB = sideB;
        this.maxIterations = Math.max(1, maxIterations);
        this.outputKey = outputKey == null ? "output.judgeAdversarial" : outputKey;
    }

    @Override
    public String name() {
        return "judgeAdversarial";
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        return Mono.fromCallable(() -> run(ctx))
                .subscribeOn(com.agent.util.SdkSchedulers.io())
                .flatMapMany(Flux::fromIterable);
    }

    private java.util.List<WorkflowEvent> run(WorkflowContext ctx) {
        java.util.List<WorkflowEvent> events = new java.util.ArrayList<>();
        String target = call(agent, judge, TARGET_PROMPT);
        String feedbackA = "（首轮，暂无反馈）";
        String feedbackB = "（首轮，暂无反馈）";
        String resultA = "";
        String resultB = "";
        boolean passA = false;
        boolean passB = false;
        for (int i = 1; i <= maxIterations; i++) {
            resultA = call(agent, sideA, SIDE_PROMPT.formatted(target, "工程效率优先", feedbackA));
            resultB = call(agent, sideB, SIDE_PROMPT.formatted(target, "稳健与可维护性优先", feedbackB));
            Judgement j = judge(target, resultA, resultB);
            passA = j.passA();
            passB = j.passB();
            events.add(new WorkflowEvent.Iteration(name(), i,
                    "A " + (passA ? "达标" : "未达标") + " / B " + (passB ? "达标" : "未达标")));
            if (passA || passB) {
                break;
            }
            feedbackA = "优点：" + j.prosA() + "；缺点：" + j.consA() + "；对方优点：" + j.prosB();
            feedbackB = "优点：" + j.prosB() + "；缺点：" + j.consB() + "；对方优点：" + j.prosA();
        }
        String winner = passA && !passB ? resultA : passB && !passA ? resultB : resultA + "\n\n---\n\n" + resultB;
        ctx.put(outputKey, winner);
        ctx.addMessage("[judgeAdversarial] " + winner);
        return events;
    }

    private record Judgement(boolean passA, boolean passB, String prosA, String consA,
                             String prosB, String consB) {
    }

    private Judgement judge(String target, String a, String b) {
        String json = call(agent, judge, JUDGE_PROMPT.formatted(target, a, b));
        try {
            JsonNode node = JacksonConfig.mapper().readTree(com.agent.output.Conversation.stripCodeFence(json));
            return new Judgement(
                    node.path("passA").asBoolean(false),
                    node.path("passB").asBoolean(false),
                    node.path("prosA").asText(""),
                    node.path("consA").asText(""),
                    node.path("prosB").asText(""),
                    node.path("consB").asText(""));
        } catch (Exception e) {
            return new Judgement(false, false, "", json, "", "");
        }
    }

    private String call(Agent a, String model, String prompt) {
        return a.chat(prompt).using(model).write();
    }
}

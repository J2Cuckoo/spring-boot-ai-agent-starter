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
 * AI 门禁：门禁模型先生成目标/研究方向，执行模型按指导产出，门禁模型判定，
 * 达标（score ≥ threshold 或 pass）放行，否则反馈微调循环，maxIterations 兜底。
 */
public final class AiGateStep implements WorkflowStep {

    private static final String INSTRUCTION_PROMPT = """
            你是门禁（gatekeeper）。请为执行方生成一段明确的目标或研究方向指令，\
            包含验收要点。只输出指令本身。""";

    private static final String WORKER_PROMPT = """
            请严格按照以下指令完成任务：\n%s\n\n如有前轮反馈，请据此改进：\n%s""";

    private static final String VERDICT_PROMPT = """
            你是门禁（gatekeeper）。判定以下结果是否达标。\
            只输出 JSON：{"pass": true/false, "score": 0到1的小数, "feedback": "改进建议"}\n\n\
            指令：\n%s\n\n结果：\n%s""";

    private final Agent agent;
    private final String gatekeeper;    // provider:model
    private final String worker;        // provider:model
    private final double threshold;
    private final int maxIterations;
    private final String outputKey;

    public AiGateStep(Agent agent, String gatekeeper, String worker,
                      double threshold, int maxIterations, String outputKey) {
        this.agent = agent;
        this.gatekeeper = gatekeeper;
        this.worker = worker;
        this.threshold = threshold;
        this.maxIterations = Math.max(1, maxIterations);
        this.outputKey = outputKey == null ? "output.aiGate" : outputKey;
    }

    @Override
    public String name() {
        return "aiGate";
    }

    @Override
    public Flux<WorkflowEvent> execute(WorkflowContext ctx) {
        return Mono.fromCallable(() -> run(ctx))
                .subscribeOn(com.agent.util.SdkSchedulers.io())
                .flatMapMany(Flux::fromIterable);
    }

    private java.util.List<WorkflowEvent> run(WorkflowContext ctx) {
        java.util.List<WorkflowEvent> events = new java.util.ArrayList<>();
        String instruction = call(agent, gatekeeper, INSTRUCTION_PROMPT);
        String feedback = "（首轮，暂无反馈）";
        String result = "";
        for (int i = 1; i <= maxIterations; i++) {
            result = call(agent, worker, WORKER_PROMPT.formatted(instruction, feedback));
            Verdict verdict = verdict(instruction, result);
            events.add(new WorkflowEvent.Iteration(name(), i,
                    "score=" + verdict.score() + (verdict.pass() ? " 达标" : " 未达标")));
            if (verdict.pass() || verdict.score() >= threshold) {
                break;
            }
            feedback = verdict.feedback();
        }
        ctx.put(outputKey, result);
        ctx.addMessage("[aiGate] " + result);
        return events;
    }

    private Verdict verdict(String instruction, String result) {
        String json = call(agent, gatekeeper, VERDICT_PROMPT.formatted(instruction, result));
        boolean pass = false;
        double score = 0;
        String feedback = "";
        try {
            String stripped = com.agent.output.Conversation.stripCodeFence(json);
            JsonNode node = JacksonConfig.mapper().readTree(stripped);
            pass = node.path("pass").asBoolean(false);
            score = node.path("score").asDouble(0);
            feedback = node.path("feedback").asText("");
        } catch (Exception ignored) {
            feedback = "判定输出无法解析，原样返回：" + json;
        }
        return new Verdict(pass, score, feedback);
    }

    private record Verdict(boolean pass, double score, String feedback) {
    }

    private String call(Agent a, String model, String prompt) {
        return a.chat(prompt).using(model).write();
    }
}

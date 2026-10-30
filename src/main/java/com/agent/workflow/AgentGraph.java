package com.agent.workflow;

import com.agent.Agent;
import com.agent.contract.ToolCall;
import com.agent.workflow.steps.AiGateStep;
import com.agent.workflow.steps.ConditionStep;
import com.agent.workflow.steps.HumanInterventionStep;
import com.agent.workflow.steps.IntentStep;
import com.agent.workflow.steps.JudgeAdversarialStep;
import com.agent.workflow.steps.LoopStep;
import com.agent.workflow.steps.ModelInferenceStep;
import com.agent.workflow.steps.ParallelStep;
import com.agent.workflow.steps.SequentialStep;
import com.agent.workflow.steps.SubWorkflowStep;
import com.agent.workflow.steps.ToolExecutionStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 工作流定义（与执行分离）：直接 {@code new AgentGraph(agent)} 创建。
 *
 * <p>定义必须以 {@code .start(输入键)} 开始、以 {@code .end(输出键)} 结尾，中间随意编排节点。
 * 定义完成后用 {@code graph.execute().input(...).output()} 启动执行。</p>
 *
 * <pre>{@code
 * AgentGraph graph = new AgentGraph(agent)
 *         .start("question")                      // 必须有输入
 *         .modelInference("answer",               // 中间随意定义
 *                 ctx -> "回答问题：" + ctx.get("question"),
 *                 "openai:fast", "answer")
 *         .end("answer");                         // 必须有输出
 *
 * String result = graph.execute()
 *         .input("question", "什么是密封接口")
 *         .output();
 * }</pre>
 *
 * <p>嵌套：{@code .subWorkflow(子图, 父输入键, 父输出键)} 引用另一个工作流；
 * 子图每次执行都获得全新的 WorkflowContext，多个工作流之间状态完全隔离，
 * 仅交换显式声明的输入与输出。</p>
 */
public final class AgentGraph {

    private final Agent agent;
    private final String name;
    private final List<WorkflowStep> steps = new ArrayList<>();
    private String inputKey;
    private String outputKey;
    private boolean ended;

    /** 直接构造：{@code new AgentGraph(agent)}。 */
    public AgentGraph(Agent agent) {
        this(agent, null);
    }

    /** 带名构造（便于嵌套引用与诊断日志）。 */
    public AgentGraph(Agent agent, String name) {
        this.agent = agent;
        this.name = name == null || name.isBlank() ? "graph" : name;
    }

    // ------------------------------------------------------------ 定义（start → ... → end）

    /** 定义起点与输入键（必须调用，且必须是第一个调用）。 */
    public AgentGraph start(String inputKey) {
        checkDefining();
        if (inputKey == null || inputKey.isBlank()) {
            throw new IllegalArgumentException("start 必须声明输入键，如 .start(\"question\")");
        }
        this.inputKey = inputKey;
        return this;
    }

    /** 定义终点与输出键（必须调用，且必须是最后一个调用）。 */
    public AgentGraph end(String outputKey) {
        checkDefining();
        if (outputKey == null || outputKey.isBlank()) {
            throw new IllegalArgumentException("end 必须声明输出键，如 .end(\"answer\")");
        }
        if (inputKey == null) {
            throw new IllegalStateException("必须先 .start(输入键) 再 .end(输出键)");
        }
        this.outputKey = outputKey;
        this.ended = true;
        return this;
    }

    /** 追加任意自定义节点。 */
    public AgentGraph node(WorkflowStep step) {
        checkDefining();
        steps.add(step);
        return this;
    }

    /** 模型推理节点：静态模型（provider:model 字符串，null 用默认模型）。 */
    public AgentGraph modelInference(String name, Function<WorkflowContext, String> promptFn,
                                     String model, String outKey) {
        return node(new ModelInferenceStep(agent, name, promptFn, model, outKey));
    }

    /** 模型推理节点：静态模型（ModelRef 配置对象，null 用默认模型）。 */
    public AgentGraph modelInference(String name, Function<WorkflowContext, String> promptFn,
                                     com.agent.model.ModelRef model, String outKey) {
        return node(new ModelInferenceStep(agent, name, promptFn, model, outKey));
    }

    /** 模型推理节点：动态模型（运行时按上下文返回 provider:model，null 用默认模型）。 */
    public AgentGraph modelInference(String name, Function<WorkflowContext, String> promptFn,
                                     Function<WorkflowContext, String> modelResolver, String outKey) {
        return node(new ModelInferenceStep(agent, name, promptFn, modelResolver, outKey));
    }

    /** 条件分支。 */
    public AgentGraph when(Predicate<WorkflowContext> condition, WorkflowStep then, WorkflowStep otherwise) {
        return node(new ConditionStep(condition, then, otherwise));
    }

    /** 并发节点。 */
    public AgentGraph parallel(WorkflowStep... items) {
        return node(new ParallelStep(List.of(items)));
    }

    /** 顺序组合节点。 */
    public AgentGraph sequential(WorkflowStep... items) {
        return node(new SequentialStep(List.of(items)));
    }

    /** 循环节点。 */
    public AgentGraph loop(Predicate<WorkflowContext> condition, WorkflowStep body, int maxIterations) {
        return node(new LoopStep(condition, body, maxIterations));
    }

    /** 人类干预节点。 */
    public AgentGraph awaitHuman(String prompt, WorkflowStep onApprove, WorkflowStep onReject) {
        return node(new HumanInterventionStep(prompt, onApprove, onReject));
    }

    /** AI 门禁节点。 */
    public AgentGraph aiGate(String gatekeeperModel, String workerModel,
                             double threshold, int maxIterations) {
        return node(new AiGateStep(agent, gatekeeperModel, workerModel, threshold, maxIterations, null));
    }

    /** 裁判对抗节点。 */
    public AgentGraph judgeAdversarial(String judgeModel, String sideAModel, String sideBModel, int maxIterations) {
        return node(new JudgeAdversarialStep(agent, judgeModel, sideAModel, sideBModel, maxIterations, null));
    }

    /** 工具执行节点。 */
    public AgentGraph tool(String name, String toolName,
                           Function<WorkflowContext, Map<String, Object>> argsFn, String outKey) {
        return node(new ToolExecutionStep(agent.tools(), name, toolName, argsFn, outKey));
    }

    /** 技能节点。 */
    public AgentGraph skill(String name, String skillId,
                            Function<WorkflowContext, Map<String, Object>> argsFn, String outKey) {
        return node(new com.agent.workflow.steps.SkillStep(
                agent.config().skillManager(), name, skillId, argsFn, outKey));
    }

    /**
     * 意图解析节点：按内置执行流程（用户输入 → 意图解析 → 匹配/加载 Skill），
     * 用 LLM 从已注册技能中选出一个，结果写入 {@code intent.skill} 与 {@code intent.args}。
     */
    public AgentGraph intent(String model, String inputKey) {
        return node(new IntentStep(agent, model, inputKey));
    }

    /**
     * 引用另一个工作流作为节点：父上下文 {@code parentInKey} 的值作为子图输入，
     * 子图输出写回父上下文 {@code parentOutKey}。子图内部状态与父图完全隔离。
     */
    public AgentGraph subWorkflow(AgentGraph sub, String parentInKey, String parentOutKey) {
        return node(new SubWorkflowStep(sub, parentInKey, parentOutKey));
    }

    // ------------------------------------------------------------ 执行

    /** 创建一次执行（每次执行使用全新上下文，互不影响）。 */
    public GraphExecution execute() {
        if (!ended || outputKey == null) {
            throw new IllegalStateException("工作流定义未完成：必须以 .start(输入键) 开始、.end(输出键) 结尾");
        }
        return new GraphExecution(this);
    }

    /** 内部：跑一次，返回输出键的值。子图嵌套也走这里（全新上下文，状态隔离）。 */
    public Object runOnce(Map<String, Object> inputs) {
        WorkflowContext ctx = new WorkflowContext();
        inputs.forEach(ctx::put);
        WorkflowStep entry = steps.size() == 1 ? steps.get(0) : new SequentialStep(steps);
        new WorkflowOrchestrator().runBlocking(entry, ctx);
        return ctx.get(outputKey);
    }

    public Agent agentRef() {
        return agent;
    }

    public String inputKey() {
        return inputKey;
    }

    public String outputKey() {
        return outputKey;
    }

    public String name() {
        return name;
    }

    /** 供嵌套引用的输入/输出契约描述。 */
    Map<String, Object> contract() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("input", inputKey);
        c.put("output", outputKey);
        return c;
    }

    private void checkDefining() {
        if (ended) {
            throw new IllegalStateException("工作流已以 .end() 完成定义，不能再修改");
        }
    }
}

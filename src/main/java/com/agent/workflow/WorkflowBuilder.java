package com.agent.workflow;

import com.agent.Agent;
import com.agent.skill.SkillManager;
import com.agent.tool.ToolExecutor;
import com.agent.workflow.steps.AiGateStep;
import com.agent.workflow.steps.ConditionStep;
import com.agent.workflow.steps.HumanInterventionStep;
import com.agent.workflow.steps.JudgeAdversarialStep;
import com.agent.workflow.steps.LoopStep;
import com.agent.workflow.steps.ModelInferenceStep;
import com.agent.workflow.steps.ParallelStep;
import com.agent.workflow.steps.SequentialStep;
import com.agent.workflow.steps.SkillStep;
import com.agent.workflow.steps.ToolExecutionStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 工作流链式构建：sequential / when / parallel / loop / awaitHuman /
 * aiGate / judgeAdversarial / modelInference / toolExecution / skillStep。
 * 所有顶层节点按声明顺序串行组合，build() 产出 {@link Workflow}。
 */
public final class WorkflowBuilder {

    private final Agent agent;
    private final List<WorkflowStep> steps = new ArrayList<>();

    public WorkflowBuilder(Agent agent) {
        this.agent = agent;
    }

    public Agent agent() {
        return agent;
    }

    public WorkflowBuilder sequential(WorkflowStep... items) {
        steps.add(new SequentialStep(List.of(items)));
        return this;
    }

    public WorkflowBuilder parallel(WorkflowStep... items) {
        steps.add(new ParallelStep(List.of(items)));
        return this;
    }

    public WorkflowBuilder loop(Predicate<WorkflowContext> condition, WorkflowStep body) {
        return loop(condition, body, 10);
    }

    public WorkflowBuilder loop(Predicate<WorkflowContext> condition, WorkflowStep body, int maxIterations) {
        steps.add(new LoopStep(condition, body, maxIterations));
        return this;
    }

    public WhenBuilder when(Predicate<WorkflowContext> condition) {
        return new WhenBuilder(this, condition);
    }

    public HumanBuilder awaitHuman(String prompt) {
        return new HumanBuilder(this, prompt);
    }

    public AiGateBuilder aiGate() {
        return new AiGateBuilder(this);
    }

    public JudgeBuilder judgeAdversarial() {
        return new JudgeBuilder(this);
    }

    /** 静态模型（provider:model 字符串，null 用默认模型）。 */
    public WorkflowBuilder modelInference(String name, Function<WorkflowContext, String> promptFn,
                                          String model, String outputKey) {
        steps.add(new ModelInferenceStep(agent, name, promptFn, model, outputKey));
        return this;
    }

    /** 静态模型（ModelRef 配置对象，null 用默认模型）。 */
    public WorkflowBuilder modelInference(String name, Function<WorkflowContext, String> promptFn,
                                          com.agent.model.ModelRef model, String outputKey) {
        steps.add(new ModelInferenceStep(agent, name, promptFn, model, outputKey));
        return this;
    }

    /** 动态模型：运行时根据上下文返回 provider:model，实现模型按需切换。 */
    public WorkflowBuilder modelInference(String name, Function<WorkflowContext, String> promptFn,
                                          Function<WorkflowContext, String> modelResolver, String outputKey) {
        steps.add(new ModelInferenceStep(agent, name, promptFn, modelResolver, outputKey));
        return this;
    }

    public WorkflowBuilder toolExecution(String name, String toolName,
                                         Function<WorkflowContext, Map<String, Object>> argsFn, String outputKey) {
        steps.add(new ToolExecutionStep(agent.tools(), name, toolName, argsFn, outputKey));
        return this;
    }

    public WorkflowBuilder skillStep(String name, String skillId,
                                     Function<WorkflowContext, Map<String, Object>> argsFn, String outputKey) {
        SkillManager manager = agent.config().skillManager();
        if (manager == null) {
            throw new IllegalStateException("未装配 SkillManager，无法添加技能步骤");
        }
        steps.add(new SkillStep(manager, name, skillId, argsFn, outputKey));
        return this;
    }

    public WorkflowBuilder step(WorkflowStep step) {
        steps.add(step);
        return this;
    }

    public Workflow build() {
        return build("workflow");
    }

    public Workflow build(String name) {
        WorkflowStep entry = steps.size() == 1 ? steps.get(0) : new SequentialStep(steps);
        return new Workflow(name, entry);
    }

    // ---------------------------------------------------------------- 子 Builder

    /** 条件分支构建：when(cond).then(a).otherwise(b)。 */
    public static final class WhenBuilder {

        private final WorkflowBuilder parent;
        private final Predicate<WorkflowContext> condition;
        private WorkflowStep thenStep;
        private WorkflowStep elseStep;

        private WhenBuilder(WorkflowBuilder parent, Predicate<WorkflowContext> condition) {
            this.parent = parent;
            this.condition = condition;
        }

        public WhenBuilder then(WorkflowStep step) {
            this.thenStep = step;
            return this;
        }

        public WorkflowBuilder otherwise(WorkflowStep step) {
            this.elseStep = step;
            return end();
        }

        public WorkflowBuilder end() {
            parent.steps.add(new ConditionStep(condition, thenStep, elseStep));
            return parent;
        }
    }

    /** 人类干预构建：awaitHuman(prompt).onApprove(a).onReject(b)。 */
    public static final class HumanBuilder {

        private final WorkflowBuilder parent;
        private final String prompt;
        private WorkflowStep approve;
        private WorkflowStep reject;

        private HumanBuilder(WorkflowBuilder parent, String prompt) {
            this.parent = parent;
            this.prompt = prompt;
        }

        public HumanBuilder onApprove(WorkflowStep step) {
            this.approve = step;
            return this;
        }

        public WorkflowBuilder onReject(WorkflowStep step) {
            this.reject = step;
            return end();
        }

        public WorkflowBuilder end() {
            parent.steps.add(new HumanInterventionStep(prompt, approve, reject));
            return parent;
        }
    }

    /** AI 门禁构建：aiGate().gatekeeper(m).worker(m).threshold(t).maxIterations(n)。 */
    public static final class AiGateBuilder {

        private final WorkflowBuilder parent;
        private String gatekeeper;
        private String worker;
        private double threshold = 0.85;
        private int maxIterations = 5;
        private String outputKey;

        private AiGateBuilder(WorkflowBuilder parent) {
            this.parent = parent;
        }

        public AiGateBuilder gatekeeper(String model) {
            this.gatekeeper = model;
            return this;
        }

        public AiGateBuilder worker(String model) {
            this.worker = model;
            return this;
        }

        public AiGateBuilder threshold(double t) {
            this.threshold = t;
            return this;
        }

        public AiGateBuilder maxIterations(int n) {
            this.maxIterations = n;
            return this;
        }

        public AiGateBuilder outputKey(String key) {
            this.outputKey = key;
            return this;
        }

        public WorkflowBuilder end() {
            if (gatekeeper == null || worker == null) {
                throw new IllegalStateException("aiGate 需要 gatekeeper 与 worker 两个模型");
            }
            parent.steps.add(new AiGateStep(parent.agent, gatekeeper, worker, threshold, maxIterations, outputKey));
            return parent;
        }
    }

    /** 裁判对抗构建：judgeAdversarial().judge(m).sideA(m).sideB(m).maxIterations(n)。 */
    public static final class JudgeBuilder {

        private final WorkflowBuilder parent;
        private String judge;
        private String sideA;
        private String sideB;
        private int maxIterations = 6;
        private String outputKey;

        private JudgeBuilder(WorkflowBuilder parent) {
            this.parent = parent;
        }

        public JudgeBuilder judge(String model) {
            this.judge = model;
            return this;
        }

        public JudgeBuilder sideA(String model) {
            this.sideA = model;
            return this;
        }

        public JudgeBuilder sideB(String model) {
            this.sideB = model;
            return this;
        }

        public JudgeBuilder maxIterations(int n) {
            this.maxIterations = n;
            return this;
        }

        public JudgeBuilder outputKey(String key) {
            this.outputKey = key;
            return this;
        }

        public WorkflowBuilder end() {
            if (judge == null || sideA == null || sideB == null) {
                throw new IllegalStateException("judgeAdversarial 需要 judge、sideA、sideB 三个模型");
            }
            parent.steps.add(new JudgeAdversarialStep(parent.agent, judge, sideA, sideB, maxIterations, outputKey));
            return parent;
        }
    }
}

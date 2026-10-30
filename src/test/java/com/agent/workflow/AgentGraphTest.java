package com.agent.workflow;

import com.agent.Agent;
import com.agent.contract.AgentJsonFormatFiled;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import com.agent.model.ProviderType;
import com.agent.transport.NettyHttpConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentGraph：定义（start → ... → end）与执行（execute → input → output）分离、
 * 多工作流嵌套状态隔离、意图解析节点。
 */
class AgentGraphTest {

    private MockWebServer server;
    private Agent agent;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        agent = Agent.builder()
                .registerProvider(Provider.builder("openai", ProviderType.OPENAI_CHAT_COMPLETIONS)
                        .baseUrl(server.url("/v1").toString())
                        .apiKey("k")
                        .build())
                .registerModel(ModelConfig.of("m", "openai", "gpt"))
                .defaultModel("openai:m")
                .transport(NettyHttpConfig.builder().workerThreads(2).maxConnectionsPerEndpoint(4).build())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        agent.close();
        server.shutdown();
    }

    private static String body(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}]}";
    }

    private static String sse(String content) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\n\n"
                + "data: [DONE]\n\n";
    }

    @Test
    void defineThenExecute() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("回答内容")));

        AgentGraph graph = new AgentGraph(agent, "qa")
                .start("question")
                .modelInference("answer",
                        ctx -> "回答：" + ctx.get("question"),
                        "openai:m", "answer")
                .end("answer");

        String result = graph.execute()
                .input("question", "什么是密封接口")
                .output();

        assertThat(result).isEqualTo("回答内容");
        // 请求里携带了渲染后的提示词
        try {
            assertThat(server.takeRequest().getBody().readUtf8()).contains("什么是密封接口");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void startAndEndAreMandatory() {
        // 未 start 直接 end
        assertThatThrownBy(() -> new AgentGraph(agent, "bad").end("out"))
                .isInstanceOf(IllegalStateException.class);
        // 未 end 就 execute
        assertThatThrownBy(() -> new AgentGraph(agent, "bad2").start("in").execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".end");
        // 缺输入就 output
        AgentGraph g = new AgentGraph(agent, "bad3").start("in").end("out");
        assertThatThrownBy(() -> g.execute().output())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少输入");
        // end 之后继续定义
        AgentGraph g2 = new AgentGraph(agent, "bad4").start("in").end("out");
        assertThatThrownBy(() -> g2.modelInference("x", ctx -> "p", (String) null, "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能再修改");
    }

    @Test
    void subWorkflowStateIsolated() {
        // 子图内部写一个中间状态；父图不可见
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("子图结果")));
        AgentGraph sub = new AgentGraph(agent, "sub")
                .start("subIn")
                .node(new WorkflowStep() {
                    @Override
                    public String name() {
                        return "mark";
                    }

                    @Override
                    public reactor.core.publisher.Flux<WorkflowEvent> execute(WorkflowContext ctx) {
                        ctx.put("sub.internal", "子图私有状态");
                        return reactor.core.publisher.Flux.empty();
                    }
                })
                .modelInference("subOut", ctx -> "处理：" + ctx.get("subIn"), "openai:m", "subOut")
                .end("subOut");

        
        WorkflowContext[] parentCtxRef = new WorkflowContext[1];
        AgentGraph parent = new AgentGraph(agent, "parent")
                .start("q")
                .subWorkflow(sub, "q", "subResult")
                .node(new WorkflowStep() {
                    @Override
                    public String name() {
                        return "capture";
                    }

                    @Override
                    public reactor.core.publisher.Flux<WorkflowEvent> execute(WorkflowContext ctx) {
                        parentCtxRef[0] = ctx;   // 抓住父上下文验证隔离
                        return reactor.core.publisher.Flux.empty();
                    }
                })
                .end("subResult");

        String out = parent.execute().input("q", "输入").output();
        assertThat(out).isEqualTo("子图结果");
        // 父上下文看不到子图内部的 sub.internal / subOut 键，只交换了声明的一对输入输出
        org.junit.jupiter.api.Assertions.assertNull(parentCtxRef[0].get("sub.internal"));
        org.junit.jupiter.api.Assertions.assertNull(parentCtxRef[0].get("subOut"));
        org.junit.jupiter.api.Assertions.assertEquals("子图结果", parentCtxRef[0].get("subResult"));
    }

    @Test
    void repeatedExecutionIsIndependent() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("第一次")));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("第二次")));
        AgentGraph graph = new AgentGraph(agent, "repeat")
                .start("q")
                .modelInference("a", ctx -> "回答", "openai:m", "a")
                .end("a");
        String first = graph.execute().input("q", "一").output();
        String second = graph.execute().input("q", "二").output();
        assertThat(first).isEqualTo("第一次");
        assertThat(second).isEqualTo("第二次");
    }

    static class Intent {
        @AgentJsonFormatFiled(desc = "选中的技能id")
        private String skill;
        @AgentJsonFormatFiled(desc = "调用参数")
        private Map<String, Object> args;
    }

    @Test
    void intentStepParsesSkillSelection() {
        // 非流式响应：content 内是转义后的意图 JSON
        com.fasterxml.jackson.databind.node.ObjectNode message =
                com.agent.serialize.JacksonConfig.mapper().createObjectNode();
        message.put("role", "assistant");
        message.put("content", "{\"skill\": \"calculator\", \"args\": {\"expression\": \"1+1\"}}");
        com.fasterxml.jackson.databind.node.ObjectNode root =
                com.agent.serialize.JacksonConfig.mapper().createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode choice =
                com.agent.serialize.JacksonConfig.mapper().createObjectNode();
        choice.set("message", message);
        choice.put("finish_reason", "stop");
        root.putArray("choices").add(choice);
        String responseBody = root.toString();
        server.enqueue(new MockResponse().setBody(responseBody));
        // 先直接验证非流式 write() 返回模型内容
        String direct = agent.chat("测试").write();

        org.junit.jupiter.api.Assertions.assertEquals(
                "{\"skill\": \"calculator\", \"args\": {\"expression\": \"1+1\"}}", direct,
                "write() 应返回 content 原文");
        server.enqueue(new MockResponse().setBody(responseBody));
        AgentGraph graph = new AgentGraph(agent, "flow")
                .start("userInput")
                .intent("openai:m", "userInput")
                .end("intent.skill");
        String skill = graph.execute().input("userInput", "帮我算 1+1").output();
        assertThat(skill).isEqualTo("calculator");
    }
}

package com.agent.workflow;

import com.agent.Agent;
import com.agent.model.ModelConfig;
import com.agent.model.ModelRef;
import com.agent.model.Provider;
import com.agent.model.ProviderType;
import com.agent.transport.NettyHttpConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentGraph 动态切换模型：modelInference 的模型由运行时函数解析，请求发往对应 model-name。
 */
class DynamicModelWorkflowTest {

    private MockWebServer server;
    private Agent agent;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        agent = Agent.builder()
                .registerProvider(Provider.builder("openai", ProviderType.OPENAI_CHAT_COMPLETIONS)
                        .baseUrl(server.url("/v1").toString())
                        .apiKey("test-key")
                        .build())
                .registerModel(ModelConfig.of("fast", "openai", "gpt-fast"))
                .registerModel(ModelConfig.of("strong", "openai", "gpt-strong"))
                .defaultModel("openai:fast")
                .transport(NettyHttpConfig.builder().workerThreads(2).maxConnectionsPerEndpoint(4).build())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        agent.close();
        server.shutdown();
    }

    private static String sse(String content) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\n\n"
                + "data: [DONE]\n\n";
    }

    @Test
    void dynamicModelResolverSwitchesModelAtRuntime() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("ok")));

        AgentGraph graph = new AgentGraph(agent)
                .start("q")
                .modelInference("step",
                        ctx -> "打个招呼",
                        ctx -> "openai:strong",            // 静态即可，动态见下一用例
                        "out")
                .end("out");
        String out = graph.execute().input("q", "x").output();
        assertThat(out).isEqualTo("ok");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"model\":\"gpt-strong\"");
    }

    @Test
    void modelRefConfigObjectAsStaticModel() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("ok")));

        AgentGraph graph = new AgentGraph(agent)
                .start("q")
                .modelInference("step",
                        ctx -> "打个招呼",
                        ModelRef.of("openai", "strong"),   // 配置对象
                        "out")
                .end("out");
        String out = graph.execute().input("q", "x").output();
        assertThat(out).isEqualTo("ok");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"model\":\"gpt-strong\"");
    }

    @Test
    void nullResolverUsesDefaultModel() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("ok")));

        AgentGraph graph = new AgentGraph(agent)
                .start("q")
                .modelInference("step",
                        ctx -> "打个招呼",
                        (java.util.function.Function<WorkflowContext, String>) ctx -> null,
                        "out")
                .end("out");
        String out = graph.execute().input("q", "x").output();
        assertThat(out).isEqualTo("ok");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"model\":\"gpt-fast\"");
    }
}

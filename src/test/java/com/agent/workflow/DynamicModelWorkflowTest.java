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
 * 工作流动态切换模型：modelInference 的模型由 Function&lt;WorkflowContext,String&gt;
 * 在运行时解析，请求发往对应的 model-name。
 */
class DynamicModelWorkflowTest {

    private MockWebServer server;
    private Agent agent;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        agent = Agent.builder()
                .registerProvider(Provider.builder("openai", ProviderType.OPENAI)
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

        // 模型由上下文决定：ctx 里的 "model" 键动态选择
        Workflow wf = agent.newWorkflow()
                .modelInference("step",
                        ctx -> "打个招呼",
                        ctx -> (String) ctx.get("model"),   // 动态模型
                        null)
                .build();

        WorkflowContext ctx = new WorkflowContext();
        ctx.put("model", "openai:strong");                  // 运行时切到 strong
        wf.runBlocking(ctx);

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"model\":\"gpt-strong\"");
        assertThat(ctx.get("output.step")).isEqualTo("ok");
    }

    @Test
    void modelRefConfigObjectAsStaticModel() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("ok")));

        Workflow wf = agent.newWorkflow()
                .modelInference("step",
                        ctx -> "打个招呼",
                        ModelRef.of("openai", "strong"),     // 配置对象，非硬编码字符串
                        null)
                .build();

        wf.runBlocking(new WorkflowContext());
        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"model\":\"gpt-strong\"");
    }

    @Test
    void nullResolverUsesDefaultModel() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("ok")));

        Workflow wf = agent.newWorkflow()
                .modelInference("step", ctx -> "hi", ctx -> null, null)   // 返回 null → 默认模型
                .build();

        wf.runBlocking(new WorkflowContext());
        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"model\":\"gpt-fast\"");   // default-model: openai:fast
    }
}

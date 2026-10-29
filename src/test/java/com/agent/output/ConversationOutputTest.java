package com.agent.output;

import com.agent.Agent;
import com.agent.contract.ChatEvent;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import com.agent.model.ProviderType;
import com.agent.transport.NettyHttpConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationOutputTest {

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
                .registerModel(ModelConfig.of("m", "openai", "gpt-test"))
                .defaultModel("openai:m")
                .transport(NettyHttpConfig.builder()
                        .workerThreads(2)
                        .maxConnectionsPerEndpoint(4)
                        .build())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        agent.close();
        server.shutdown();
    }

    private static String openaiBody(String content) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = com.agent.serialize.JacksonConfig.mapper();
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode choice = body.putArray("choices").addObject();
        com.fasterxml.jackson.databind.node.ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        choice.put("finish_reason", "stop");
        com.fasterxml.jackson.databind.node.ObjectNode usage = body.putObject("usage");
        usage.put("prompt_tokens", 3);
        usage.put("completion_tokens", 4);
        usage.put("total_tokens", 7);
        return body.toString();
    }

    @Test
    void writeAggregatesBlockingResponse() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(openaiBody("你好，世界")));
        String out = agent.chat("打个招呼").write();
        assertThat(out).isEqualTo("你好，世界");

        okhttp3.mockwebserver.RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key");
        // 非流式：省略 stream 字段（OpenAI 语义缺省即非流式）
        assertThat(body).doesNotContain("\"stream\":true");
        assertThat(body).contains("打个招呼");
    }

    @Test
    void systemTemplateAndTemperatureTakeEffect() throws Exception {
        server.enqueue(new MockResponse().setBody(openaiBody("ok")));
        String out = agent.chat("你好")
                .systemTemplate("你是一名{role}，用{lang}回答")
                .variable("role", "资深架构师")
                .variable("lang", "中文")
                .temperature(0.3)
                .write();
        assertThat(out).isEqualTo("ok");
        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("你是一名资深架构师，用中文回答");
        assertThat(body).contains("\"temperature\":0.3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeTypeParsesAndStripsFence() throws Exception {
        server.enqueue(new MockResponse().setBody(openaiBody("```json\n{\"city\":\"北京\",\"temp\":26}\n```")));
        Map<String, Object> weather = agent.chat("北京天气").write(Map.class);
        assertThat(weather).containsEntry("city", "北京").containsEntry("temp", 26);

        okhttp3.mockwebserver.RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getBody().readUtf8()).contains("json_object");
    }

    @Test
    void writeTypeFailsAfterRetry() {
        server.enqueue(new MockResponse().setBody(openaiBody("这不是 JSON")));
        server.enqueue(new MockResponse().setBody(openaiBody("仍然不是 JSON")));
        assertThatThrownBy(() -> agent.chat("输出 JSON").write(Map.class))
                .isInstanceOf(JsonOutputException.class);
    }

    @Test
    void streamSubscribeWriteEmitsEventsInOrder() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}\n\n"
                        + "data: [DONE]\n\n"));
        StringBuilder text = new StringBuilder();
        java.util.List<String> finishes = new java.util.ArrayList<>();
        // 用户 DSL：.stream() → .subscribe(...) → .write()（write 永远是结尾，阻塞至流完成）
        agent.chat("写问候")
                .stream()
                .subscribe(e -> {
                    if (e instanceof ChatEvent.TokenEvent t) {
                        text.append(t.delta());
                    }
                })
                .subscribe(new com.agent.contract.ChatStreamSubscriber() {
                    @Override
                    public void onComplete(String finishReason) {
                        finishes.add(finishReason);
                    }
                })
                .write();
        assertThat(text.toString()).isEqualTo("Hello");
        assertThat(finishes).contains("stop");   // DoneEvent 的 finishReason 已分发
    }

    @Test
    void streamModeLocksBlockingTerminals() {
        server.enqueue(new MockResponse().setBody(openaiBody("x")));
        com.agent.output.Conversation c = agent.chat("你好");
        c.stream();                                       // 进入流式模式
        org.assertj.core.api.Assertions.assertThatThrownBy(c::write)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("流式模式");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.temperature(0.5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("流式模式");
    }

    @Test
    void writeAgentFileRefReturnsMetadata(@TempDir Path dir) throws Exception {
        server.enqueue(new MockResponse().setBody(openaiBody("报告正文")));
        AgentFile file = agent.chat("生成报告")
                .write(AgentFileRef.of(dir.toString(), "report.json"));

        Path written = dir.resolve("report.json");
        assertThat(written).exists();
        String content = Files.readString(written);
        assertThat(content).contains("\"format\" : \"text\"");
        assertThat(content).contains("\"model\" : \"openai:m\"");
        assertThat(content).contains("报告正文");
        assertThat(content).contains("\"totalTokens\" : 7");

        // AgentFile 元数据完整
        assertThat(file.absolutePath()).isEqualTo(written.toAbsolutePath().toString());
        assertThat(file.relativePath()).isEqualTo(dir.toString());
        assertThat(file.fileName()).isEqualTo("report.json");
        assertThat(file.fileType()).isEqualTo("json");
        assertThat(file.sizeBytes()).isEqualTo(Files.size(written));
        // 国密 SM3：64 位十六进制
        assertThat(file.sm3()).matches("[0-9a-f]{64}");
        assertThat(file.finishedAt()).isNotBlank();
    }

    @Test
    void terminalMethodsAreMutuallyExclusive() {
        server.enqueue(new MockResponse().setBody(openaiBody("x")));
        com.agent.output.Conversation c = agent.chat("你好");
        c.write();
        assertThatThrownBy(c::write)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("互斥");
    }
}

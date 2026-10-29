package com.agent.output;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @AgentJsonFormatFiled 注解：write(Class) 时 POJO 字段必须标注注解；
 * 模型请求中注入字段描述与格式约束。
 */
class AgentJsonFormatFieldTest {

    static class Weather {
        @AgentJsonFormatFiled(desc = "城市名称")
        private String city;
        @AgentJsonFormatFiled(desc = "观测日期", format = "yyyy-MM-dd")
        private String date;
        @AgentJsonFormatFiled(desc = "摄氏温度")
        private Double temp;
        // 无注解字段：模型不被要求输出
        private String internal;

        public String getCity() { return city; }
        public String getDate() { return date; }
        public Double getTemp() { return temp; }
    }

    static class NoAnnotation {
        private String a;
        private String b;
    }

    private MockWebServer server;
    private Agent agent;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        agent = Agent.builder()
                .registerProvider(Provider.builder("openai", ProviderType.OPENAI)
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

    @Test
    void schemaWithDescAndFormatInjectedIntoRequest() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"city\\\":\\\"北京\\\"}\"},\"finish_reason\":\"stop\"}]}"));
        Weather w = agent.chat("天气").write(Weather.class);
        assertThat(w.getCity()).isEqualTo("北京");

        String body = server.takeRequest().getBody().readUtf8();
        // 请求中注入了字段约束：desc 与 format
        assertThat(body).contains("城市名称");
        assertThat(body).contains("yyyy-MM-dd");
        assertThat(body).contains("摄氏温度");
        // 只输出注解字段
        assertThat(body).contains("只包含以下字段");
    }

    @Test
    void pojoWithoutAnnotationRejected() {
        server.enqueue(new MockResponse().setBody("{}"));
        assertThatThrownBy(() -> agent.chat("...").write(NoAnnotation.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@AgentJsonFormatFiled");
    }

    @Test
    void mapStillWorksWithoutAnnotation() {
        // 非字段目标（Map）不受注解约束，沿用通用 JSON 指令
        server.enqueue(new MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = agent.chat("输出").write(java.util.Map.class);
        assertThat(result).containsEntry("ok", true);
    }
}

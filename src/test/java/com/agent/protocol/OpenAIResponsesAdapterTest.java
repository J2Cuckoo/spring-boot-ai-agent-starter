package com.agent.protocol;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.contract.Message;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import com.agent.model.ProviderType;
import com.agent.protocol.openai.OpenAIResponsesAdapter;
import com.agent.transport.SseFrame;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI Responses API 适配：请求体结构、流式事件解析、非流式响应解析。
 */
class OpenAIResponsesAdapterTest {

    private final OpenAIResponsesAdapter adapter = new OpenAIResponsesAdapter();
    private final Provider provider = Provider.builder("openai", ProviderType.OPENAI_RESPONSES)
            .baseUrl("https://api.openai.com/v1").apiKey("sk-t").build();
    private final ModelConfig model = ModelConfig.of("r", "openai", "gpt-5");

    @Test
    void typeAndFrameMode() {
        assertThat(adapter.type()).isEqualTo(ProviderType.OPENAI_RESPONSES);
        assertThat(adapter.frameMode()).isEqualTo(com.agent.transport.FrameMode.SSE);
    }

    @Test
    void buildRequestBody() {
        ChatRequest request = ChatRequest.builder()
                .addMessage(Message.system("你是助手"))
                .addMessage(Message.user("你好"))
                .temperature(0.2)
                .build();
        com.agent.transport.HttpRequestSpec spec = adapter.buildRequest(provider, model, request, true);
        String body = new String(spec.body(), StandardCharsets.UTF_8);
        assertThat(spec.uri()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(body).contains("\"instructions\":\"你是助手\"");
        assertThat(body).contains("\"input\":");
        assertThat(body).contains("\"temperature\":0.2");
        assertThat(body).contains("\"stream\":true");
        assertThat(body).contains("\"model\":\"gpt-5\"");
    }

    @Test
    void parseStreamingEvents() {
        List<ChatEvent> events = adapter.parse(FluxHelper.just(
                        frame("response.output_text.delta", "{\"delta\":\"你\"}"),
                        frame("response.output_text.delta", "{\"delta\":\"好\"}"),
                        frame("response.completed",
                                "{\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}}")))
                .collectList().block();
        assertThat(events).hasSize(4);
        assertThat(((ChatEvent.TokenEvent) events.get(0)).delta()).isEqualTo("你");
        assertThat(((ChatEvent.TokenEvent) events.get(1)).delta()).isEqualTo("好");
        ChatEvent.UsageEvent usage = (ChatEvent.UsageEvent) events.get(2);
        assertThat(usage.totalTokens()).isEqualTo(5);
        assertThat(events.get(3)).isInstanceOf(ChatEvent.DoneEvent.class);
    }

    @Test
    void parseNonStreamingResponse() {
        String json = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"答案\"}]}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}";
        List<ChatEvent> events = adapter.parseResponse(
                        new com.agent.transport.HttpResponse(200, java.util.Map.of(),
                                json.getBytes(StandardCharsets.UTF_8)))
                .collectList().block();
        assertThat(events).hasSize(3);
        assertThat(((ChatEvent.TokenEvent) events.get(0)).delta()).isEqualTo("答案");
        assertThat(events.get(2)).isInstanceOf(ChatEvent.DoneEvent.class);
    }

    private static SseFrame frame(String event, String data) {
        return new SseFrame(event, Unpooled.wrappedBuffer(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** 小工具：多个 SseFrame 直接组流。 */
    static final class FluxHelper {
        static reactor.core.publisher.Flux<SseFrame> just(SseFrame... frames) {
            return reactor.core.publisher.Flux.fromArray(frames);
        }
    }
}

package com.agent.transport;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NettyHttpClientTest {

    private MockWebServer server;
    private NettyHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new NettyHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }

    private String url(String path) {
        return server.url(path).toString();
    }

    @Test
    void blockingExecuteRoundTrip() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true}"));
        HttpResponse resp = client.execute(HttpRequestSpec.builder()
                        .uri(url("/v1/test"))
                        .header("Authorization", "Bearer k")
                        .body("{\"q\":1}")
                        .build())
                .block(java.time.Duration.ofSeconds(10));
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.bodyText()).isEqualTo("{\"ok\":true}");

        okhttp3.mockwebserver.RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/test");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer k");
        assertThat(recorded.getBody().readUtf8()).isEqualTo("{\"q\":1}");
    }

    @Test
    void streamingSseFrames() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: one\ndata: {\"a\":1}\n\nevent: two\ndata: [DONE]\n\n"));
        List<SseFrame> frames = client.executeStreaming(
                        HttpRequestSpec.builder().uri(url("/stream")).build(), FrameMode.SSE)
                .collectList()
                .block(java.time.Duration.ofSeconds(10));
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).event()).isEqualTo("one");
        assertThat(frames.get(0).dataAsText()).isEqualTo("{\"a\":1}");
        assertThat(frames.get(1).event()).isEqualTo("two");
        assertThat(frames.get(1).dataAsText()).isEqualTo("[DONE]");
        frames.forEach(SseFrame::release);
    }

    @Test
    void streamingNdjsonFrames() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody("{\"m\":\"hi\"}\n{\"m\":\"!\",\"done\":true}\n"));
        List<SseFrame> frames = client.executeStreaming(
                        HttpRequestSpec.builder().uri(url("/api/chat")).build(), FrameMode.NDJSON)
                .collectList()
                .block(java.time.Duration.ofSeconds(10));
        assertThat(frames).hasSize(2);
        assertThat(frames.get(1).dataAsText()).contains("\"done\":true");
        frames.forEach(SseFrame::release);
    }

    @Test
    void httpErrorSurfacesAsAgentException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        Flux<SseFrame> flux = client.executeStreaming(
                HttpRequestSpec.builder().uri(url("/bad")).build(), FrameMode.SSE);
        reactor.test.StepVerifier.create(flux)
                .expectErrorSatisfies(t -> {
                    assertThat(t).isInstanceOf(com.agent.contract.AgentException.class);
                    assertThat(t.getMessage()).contains("500").contains("boom");
                })
                .verify(java.time.Duration.ofSeconds(10));
    }
}

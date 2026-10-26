package com.agent.protocol;

import com.agent.contract.ChatEvent;
import com.agent.protocol.claude.ClaudeEventParser;
import com.agent.protocol.ollama.OllamaChatEventParser;
import com.agent.protocol.openai.OpenAIChatEventParser;
import com.agent.transport.SseFrame;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventParserTest {

    private static SseFrame frame(String event, String data) {
        return new SseFrame(event, Unpooled.wrappedBuffer(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void openaiTokensUsageDoneAndToolAccumulation() {
        OpenAIChatEventParser parser = new OpenAIChatEventParser();
        List<ChatEvent> tokens = parser.parse(frame(null,
                "{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}"));
        assertThat(tokens).hasSize(1);
        assertThat(((ChatEvent.TokenEvent) tokens.get(0)).delta()).isEqualTo("你好");

        // 工具增量累积
        parser.parse(frame(null,
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"calc\",\"arguments\":\"{\\\"expr\\\":\"}}]}}]}"));
        List<ChatEvent> more = parser.parse(frame(null,
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"1+1\\\"}\"}}]}}]}"));
        assertThat(more).hasSize(1);
        assertThat(((ChatEvent.ToolCallDeltaEvent) more.get(0)).name()).isEqualTo("calc");

        List<ChatEvent> finish = parser.parse(frame(null,
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"));
        assertThat(finish).hasSize(1);   // usage
        assertThat(((ChatEvent.UsageEvent) finish.get(0)).totalTokens()).isEqualTo(15);

        List<ChatEvent> done = parser.parse(frame(null, "[DONE]"));
        // ToolCallComplete + Done
        assertThat(done).hasSize(2);
        ChatEvent.ToolCallCompleteEvent complete = (ChatEvent.ToolCallCompleteEvent) done.get(0);
        assertThat(complete.arguments()).containsEntry("expr", "1+1");
        assertThat(((ChatEvent.DoneEvent) done.get(1)).finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void openaiDoneFallbackWhenNoMarker() {
        OpenAIChatEventParser parser = new OpenAIChatEventParser();
        parser.parse(frame(null, "{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}"));
        List<ChatEvent> done = parser.done();
        assertThat(done).hasSize(1);
        assertThat(((ChatEvent.DoneEvent) done.get(0)).finishReason()).isEqualTo("stop");
    }

    @Test
    void claudeFullStreamLifecycle() {
        ClaudeEventParser parser = new ClaudeEventParser();
        assertThat(parser.parse(frame("ping", "{}"))).hasSize(1);   // HeartbeatEvent

        parser.parse(frame("message_start",
                "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7}}}"));

        parser.parse(frame("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"calc\"}}"));
        parser.parse(frame("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\":1}\"}}"));
        List<ChatEvent> blockStop = parser.parse(frame("content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":1}"));
        assertThat(blockStop).hasSize(1);
        ChatEvent.ToolCallCompleteEvent tc = (ChatEvent.ToolCallCompleteEvent) blockStop.get(0);
        assertThat(tc.id()).isEqualTo("tu_1");
        assertThat(tc.arguments()).containsEntry("a", 1);

        parser.parse(frame("message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}"));
        List<ChatEvent> stop = parser.parse(frame("message_stop", "{\"type\":\"message_stop\"}"));
        assertThat(stop).hasSize(2);
        assertThat(((ChatEvent.UsageEvent) stop.get(0)).totalTokens()).isEqualTo(10);
        assertThat(((ChatEvent.DoneEvent) stop.get(1)).finishReason()).isEqualTo("end_turn");
    }

    @Test
    void ollamaNdjsonDoneFrame() {
        OllamaChatEventParser parser = new OllamaChatEventParser();
        List<ChatEvent> tokens = parser.parse(frame(null,
                "{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"done\":false}"));
        assertThat(tokens).hasSize(1);
        List<ChatEvent> last = parser.parse(frame(null,
                "{\"message\":{\"role\":\"assistant\",\"content\":\"!\"},\"done\":true,"
                        + "\"prompt_eval_count\":4,\"eval_count\":6}"));
        assertThat(last).hasSize(3);
        assertThat(((ChatEvent.UsageEvent) last.get(1)).totalTokens()).isEqualTo(10);
        assertThat(((ChatEvent.DoneEvent) last.get(2)).finishReason()).isEqualTo("stop");
    }

    @Test
    void geminiStreamParsing() {
        com.agent.protocol.gemini.GeminiEventParser parser = new com.agent.protocol.gemini.GeminiEventParser();
        List<ChatEvent> events = parser.parse(frame(null,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"答案\"}],\"role\":\"model\"}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":3,\"totalTokenCount\":5}}"));
        assertThat(events).hasSize(2);
        assertThat(((ChatEvent.TokenEvent) events.get(0)).delta()).isEqualTo("答案");
        assertThat(((ChatEvent.UsageEvent) events.get(1)).totalTokens()).isEqualTo(5);

        List<ChatEvent> finish = parser.parse(frame(null,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"。\"}]},\"finishReason\":\"STOP\"}]}"));
        assertThat(((ChatEvent.DoneEvent) finish.get(1)).finishReason()).isEqualTo("STOP");
    }
}

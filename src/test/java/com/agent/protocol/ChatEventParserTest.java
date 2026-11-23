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

    // ---------------------------------------------------------------- Responses API

    @Test
    void openaiResponsesFullLifecycle() {
        com.agent.protocol.openai.OpenAIResponsesEventParser parser =
                new com.agent.protocol.openai.OpenAIResponsesEventParser();

        // response.created / in_progress 等生命周期事件忽略
        assertThat(parser.parse(frame("response.created", "{\"type\":\"response.created\"}"))).isEmpty();
        assertThat(parser.parse(frame("response.in_progress", "{\"type\":\"response.in_progress\"}"))).isEmpty();

        // 增量文本
        List<ChatEvent> delta = parser.parse(frame("response.output_text.delta",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}"));
        assertThat(delta).hasSize(1);
        assertThat(((ChatEvent.TokenEvent) delta.get(0)).delta()).isEqualTo("你好");

        // completed：usage + done 一次给齐，done() 不再补发
        List<ChatEvent> completed = parser.parse(frame("response.completed",
                "{\"type\":\"response.completed\",\"response\":{\"usage\":"
                        + "{\"input_tokens\":7,\"output_tokens\":8,\"total_tokens\":15}}}"));
        assertThat(completed).hasSize(2);
        assertThat(((ChatEvent.UsageEvent) completed.get(0)).totalTokens()).isEqualTo(15);
        assertThat(((ChatEvent.DoneEvent) completed.get(1)).finishReason()).isEqualTo("stop");
        assertThat(parser.done()).isEmpty();
    }

    @Test
    void openaiResponsesDoneFallbackWithoutCompleted() {
        com.agent.protocol.openai.OpenAIResponsesEventParser parser =
                new com.agent.protocol.openai.OpenAIResponsesEventParser();
        parser.parse(frame("response.output_text.delta", "{\"delta\":\"x\"}"));
        List<ChatEvent> done = parser.done();
        assertThat(done).hasSize(1);
        assertThat(((ChatEvent.DoneEvent) done.get(0)).finishReason()).isEqualTo("stop");
    }

    @Test
    void openaiResponsesFailedAndErrorEvents() {
        com.agent.protocol.openai.OpenAIResponsesEventParser parser =
                new com.agent.protocol.openai.OpenAIResponsesEventParser();
        List<ChatEvent> failed = parser.parse(frame("response.failed",
                "{\"response\":{\"error\":{\"message\":\"上下文超限\"}}}"));
        assertThat(((ChatEvent.ErrorEvent) failed.get(0)).code()).isEqualTo("MODEL_ERROR");

        List<ChatEvent> error = parser.parse(frame("error", "{\"message\":\"限流\"}"));
        assertThat(((ChatEvent.ErrorEvent) error.get(0)).message()).isEqualTo("限流");
    }

    // ---------------------------------------------------------------- 坏帧容错

    @Test
    void malformedJsonFrameBecomesErrorEventNotThrow() {
        // 传输层按 SSE 帧切分，坏 JSON（截断/非 JSON）不应炸掉解析器，转为 ErrorEvent
        OpenAIChatEventParser chatParser = new OpenAIChatEventParser();
        List<ChatEvent> bad = chatParser.parse(frame(null, "{\"choices\":[{\"delta\":{\"con"));
        assertThat(bad).hasSize(1);
        assertThat(((ChatEvent.ErrorEvent) bad.get(0)).code()).isEqualTo("MODEL_ERROR");

        com.agent.protocol.openai.OpenAIResponsesEventParser responsesParser =
                new com.agent.protocol.openai.OpenAIResponsesEventParser();
        List<ChatEvent> badResponses = responsesParser.parse(frame("response.output_text.delta", "{broken"));
        assertThat(badResponses).hasSize(1);
        assertThat(((ChatEvent.ErrorEvent) badResponses.get(0)).code()).isEqualTo("MODEL_ERROR");
    }

    @Test
    void openaiErrorFrameInStream() {
        OpenAIChatEventParser parser = new OpenAIChatEventParser();
        List<ChatEvent> events = parser.parse(frame(null,
                "{\"error\":{\"message\":\"配额不足\",\"type\":\"insufficient_quota\"}}"));
        assertThat(events).hasSize(1);
        assertThat(((ChatEvent.ErrorEvent) events.get(0)).message()).isEqualTo("配额不足");
    }
}

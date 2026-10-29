package com.agent.output;

import com.agent.rag.RagOptions;

import java.util.function.Consumer;

/**
 * 请求级 RAG 设置：{@code agent.chat("...").rag().maxResults(5).minScore(0.6).write()}。
 * <p>设置方法返回自身可继续链式；{@code end()} 回到 Conversation，
 * 也可直接在本对象上以 {@code write} 终结（与 Conversation 的终结语义一致）。</p>
 */
public final class RagSettings {

    private final Conversation conversation;
    private Integer maxResults;
    private Double minScore;

    RagSettings(Conversation conversation) {
        this.conversation = conversation;
    }

    /** 知识库最多返回 N 条结果（覆盖全局 rag.top-k）。 */
    public RagSettings maxResults(int n) {
        this.maxResults = n;
        return this;
    }

    /** 召回分数下限，低于该分数的命中被过滤（默认不过滤）。 */
    public RagSettings minScore(double score) {
        this.minScore = score;
        return this;
    }

    /** 回到 Conversation 继续其他配置。 */
    public Conversation end() {
        return conversation;
    }

    RagOptions toOptions() {
        return new RagOptions(maxResults, minScore);
    }

    // ------------------------------------------------------------ 终结委托（与 Conversation 一致）

    public String write() {
        return conversation.write();
    }

    public <T> T write(Class<T> type) {
        return conversation.write(type);
    }

    public AgentFile write(AgentFileRef fileRef) {
        return conversation.write(fileRef);
    }

    public StreamingConversation stream() {
        return conversation.stream();
    }

    /** 便捷：consumer 形式配置。 */
    static RagSettings configure(Conversation c, Consumer<RagSettings> configurer) {
        RagSettings settings = new RagSettings(c);
        configurer.accept(settings);
        return settings;
    }
}

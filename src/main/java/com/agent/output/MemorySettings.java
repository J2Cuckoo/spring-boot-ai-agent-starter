package com.agent.output;

import com.agent.memory.MemoryOptions;
import com.agent.memory.MemoryTier;

/**
 * 请求级记忆设置：{@code agent.chat("...").memory().tier(MemoryTier.SHORT_TERM).write()}。
 * <p>设置方法返回自身可继续链式；{@code end()} 回到 Conversation，
 * 也可直接在本对象上以 {@code write} 终结（与 Conversation 的终结语义一致）。</p>
 */
public final class MemorySettings {

    private final Conversation conversation;
    private Boolean enabled;
    private MemoryTier tier;
    private String namespace;

    MemorySettings(Conversation conversation) {
        this.conversation = conversation;
    }

    /** 单次调用开启/关闭记忆（仅当全局已装配记忆拦截器时有效）。 */
    public MemorySettings enabled(boolean value) {
        this.enabled = value;
        return this;
    }

    /** 单次调用只查询指定记忆层。 */
    public MemorySettings tier(MemoryTier tier) {
        this.tier = tier;
        return this;
    }

    /** 覆盖记忆命名空间（默认 provider:model）。 */
    public MemorySettings namespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    /** 回到 Conversation 继续其他配置。 */
    public Conversation end() {
        return conversation;
    }

    MemoryOptions toOptions() {
        return new MemoryOptions(enabled, tier, namespace);
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
}

package com.agent.util;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * SDK 专属阻塞 IO 调度器：记忆 Redis 读写、RAG 检索、工具执行、门禁改写、
 * 工作流模型调用等阻塞操作统一走此池，与宿主共享的
 * {@code Schedulers.boundedElastic()} 隔离，避免高并发下互相排队拖垮。
 * <p>规模参数可用系统属性调整：{@code agent.io.threads}（默认 max(32, CPU×8)）、
 * {@code agent.io.queue}（默认 200000）。队列打满时新任务快速失败
 * （Reactor boundedElastic 语义），由容错层重试兜底，防止无界排队吃光内存。
 */
public final class SdkSchedulers {

    private static final Scheduler IO = Schedulers.newBoundedElastic(
            Integer.getInteger("agent.io.threads",
                    Math.max(32, Runtime.getRuntime().availableProcessors() * 8)),
            Integer.getInteger("agent.io.queue", 200_000),
            "agent-io",
            60,
            true);

    private SdkSchedulers() {
    }

    public static Scheduler io() {
        return IO;
    }
}

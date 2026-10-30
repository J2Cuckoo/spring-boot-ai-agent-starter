package com.agent.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Milvus 客户端连接池。Milvus Java SDK 每个客户端对应一条 gRPC channel，
 * 官方未提供池；本池维护 N 个客户端做借还，分摊单 channel 压力并在
 * channel 异常时提供健康实例切换。
 *
 * <p>借出超时抛 {@link IllegalStateException}；用完必须 {@link #release} 归还；
 * 出现不可恢复故障可调 {@link #discard} 丢弃并补充新实例。</p>
 */
public final class MilvusClientPool implements AutoCloseable {

    private final Supplier<MilvusServiceClient> creator;
    private final int maxSize;
    private final long acquireTimeoutMs;
    private final BlockingQueue<MilvusServiceClient> idle;
    private final List<MilvusServiceClient> all;
    private volatile boolean closed;

    public MilvusClientPool(ConnectParam connectParam, int maxSize, long acquireTimeoutMs) {
        this(() -> new MilvusServiceClient(connectParam), maxSize, acquireTimeoutMs);
    }

    public MilvusClientPool(Supplier<MilvusServiceClient> creator, int maxSize, long acquireTimeoutMs) {
        this.creator = creator;
        this.maxSize = Math.max(1, maxSize);
        this.acquireTimeoutMs = Math.max(100, acquireTimeoutMs);
        this.idle = new ArrayBlockingQueue<>(this.maxSize);
        this.all = new ArrayList<>(this.maxSize);
        for (int i = 0; i < this.maxSize; i++) {
            MilvusServiceClient client = creator.get();
            all.add(client);
            idle.add(client);
        }
    }

    public int maxSize() {
        return maxSize;
    }

    public int idleCount() {
        return idle.size();
    }

    /** 借出一个客户端；超时抛异常。 */
    public MilvusServiceClient borrow() {
        if (closed) {
            throw new IllegalStateException("Milvus 连接池已关闭");
        }
        MilvusServiceClient client;
        try {
            client = idle.poll(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Milvus 客户端被中断", e);
        }
        if (client == null) {
            throw new IllegalStateException("获取 Milvus 客户端超时（" + acquireTimeoutMs
                    + "ms），池大小 " + maxSize + "，可调大 rag.milvus.pool.max-size");
        }
        return client;
    }

    /** 归还客户端。 */
    public void release(MilvusServiceClient client) {
        if (client == null || closed) {
            return;
        }
        if (!idle.offer(client)) {
            // 非本池实例或重复归还，忽略
        }
    }

    /** 丢弃故障客户端并补充新实例，保持池大小不变。 */
    public synchronized void discard(MilvusServiceClient client) {
        if (closed) {
            return;
        }
        try {
            client.close(1);
        } catch (Exception ignored) {
            // 关闭失败不影响补新
        }
        all.remove(client);
        MilvusServiceClient fresh = creator.get();
        all.add(fresh);
        idle.offer(fresh);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (MilvusServiceClient client : all) {
            try {
                client.close(1);
            } catch (Exception ignored) {
                // 关闭失败忽略
            }
        }
        all.clear();
        idle.clear();
    }
}

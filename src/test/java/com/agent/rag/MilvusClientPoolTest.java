package com.agent.rag;

import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * Milvus 连接池：借还、超时、discard 补充、close。
 */
class MilvusClientPoolTest {

    private MilvusServiceClient mockClient() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        try {
            doNothing().when(client).close(anyLong());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return client;
    }

    @Test
    void borrowAndRelease() {
        AtomicInteger created = new AtomicInteger();
        MilvusClientPool pool = new MilvusClientPool(() -> {
            created.incrementAndGet();
            return mockClient();
        }, 2, 1000);
        try {
            assertThat(pool.maxSize()).isEqualTo(2);
            MilvusServiceClient a = pool.borrow();
            MilvusServiceClient b = pool.borrow();
            assertThat(pool.idleCount()).isZero();
            pool.release(a);
            pool.release(b);
            assertThat(pool.idleCount()).isEqualTo(2);
            // 全部归还后可再借
            assertThat(pool.borrow()).isNotNull();
            assertThat(created.get()).isEqualTo(2);   // 未新建
        } finally {
            pool.close();
        }
    }

    @Test
    void borrowTimesOutWhenExhausted() {
        MilvusClientPool pool = new MilvusClientPool(this::mockClient, 1, 150);
        try {
            MilvusServiceClient only = pool.borrow();
            assertThatThrownBy(pool::borrow)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("超时");
            pool.release(only);
        } finally {
            pool.close();
        }
    }

    @Test
    void discardReplacesClient() {
        List<MilvusServiceClient> made = new ArrayList<>();
        MilvusClientPool pool = new MilvusClientPool(() -> {
            MilvusServiceClient c = mockClient();
            made.add(c);
            return c;
        }, 1, 1000);
        try {
            MilvusServiceClient first = pool.borrow();
            pool.discard(first);   // 丢弃故障客户端，自动补新
            assertThat(pool.idleCount()).isEqualTo(1);
            assertThat(made.size()).isEqualTo(2);
            assertThat(pool.borrow()).isNotSameAs(first);
        } finally {
            pool.close();
        }
    }

    @Test
    void closedPoolRejectsBorrow() {
        MilvusClientPool pool = new MilvusClientPool(this::mockClient, 1, 100);
        pool.close();
        assertThatThrownBy(pool::borrow).isInstanceOf(IllegalStateException.class);
    }
}

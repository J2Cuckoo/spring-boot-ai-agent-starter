package com.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;

import java.io.IOException;

/**
 * 零拷贝 JSON 解析：直接在 ByteBuf 上流式解析，不先整段转 String。
 */
public final class ZeroCopyJsonParser {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = JacksonConfig.mapper();

    public static JsonNode readTree(ByteBuf buf) throws IOException {
        return MAPPER.readTree(new ByteBufInputStream(buf));
    }

    public static JsonNode readTree(byte[] bytes) throws IOException {
        return MAPPER.readTree(bytes);
    }

    public static <T> T readValue(ByteBuf buf, Class<T> type) throws IOException {
        // ByteBufInputStream 同时实现 DataInput，需显式选 InputStream 重载
        return MAPPER.readValue((java.io.InputStream) new ByteBufInputStream(buf), type);
    }

    private ZeroCopyJsonParser() {
    }
}

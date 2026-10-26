package com.agent.serialize;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 共享 Jackson 配置：忽略未知字段，统一时间格式。
 */
public final class JacksonConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private JacksonConfig() {
    }
}

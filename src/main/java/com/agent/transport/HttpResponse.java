package com.agent.transport;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 传输层响应：状态、头与聚合后的响应体。
 */
public record HttpResponse(int status, Map<String, String> headers, byte[] body) {

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}

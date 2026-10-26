package com.agent.transport;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 传输层请求：方法、完整 URI、头与请求体。
 */
public final class HttpRequestSpec {

    private final String method;
    private final String uri;
    private final Map<String, String> headers;
    private final byte[] body;

    private HttpRequestSpec(Builder b) {
        this.method = b.method;
        this.uri = b.uri;
        this.headers = Map.copyOf(b.headers);
        this.body = b.body == null ? new byte[0] : b.body;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String method() {
        return method;
    }

    public String uri() {
        return uri;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] body() {
        return body;
    }

    public static final class Builder {

        private String method = "POST";
        private String uri;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body;

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Builder body(String json) {
            this.body = json == null ? null : json.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        public Builder body(byte[] body) {
            this.body = body;
            return this;
        }

        public HttpRequestSpec build() {
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("uri 不能为空");
            }
            return new HttpRequestSpec(this);
        }
    }
}

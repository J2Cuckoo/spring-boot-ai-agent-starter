package com.agent.protocol;

import com.agent.contract.ChatEvent;
import com.agent.contract.ChatRequest;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import com.agent.model.ProviderType;
import com.agent.transport.FrameMode;
import com.agent.transport.HttpRequestSpec;
import com.agent.transport.HttpResponse;
import com.agent.transport.SseFrame;
import reactor.core.publisher.Flux;

/**
 * 协议适配器：统一 ChatRequest 与厂商协议的互转。
 * 新增 Provider 只需实现本接口并注册进 {@link ProtocolAdapterFactory}。
 */
public interface ProtocolAdapter {

    ProviderType type();

    /** 该协议的流式帧格式（SSE 或 NDJSON）。 */
    FrameMode frameMode();

    /** 序列化请求（在拦截器修改完 ChatRequest 之后调用）。 */
    HttpRequestSpec buildRequest(Provider provider, ModelConfig model, ChatRequest request, boolean stream);

    /** 流式：厂商帧 → 统一事件流。 */
    Flux<ChatEvent> parse(Flux<SseFrame> frames);

    /** 非流式：聚合响应 → 统一事件流。 */
    Flux<ChatEvent> parseResponse(HttpResponse response);
}

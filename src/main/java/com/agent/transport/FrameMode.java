package com.agent.transport;

/**
 * 流式帧格式：SSE（`\n\n` 事件帧）或 NDJSON（单行帧，Ollama）。
 */
public enum FrameMode {
    SSE,
    NDJSON
}

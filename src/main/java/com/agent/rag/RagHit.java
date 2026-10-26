package com.agent.rag;

/**
 * 检索命中。
 */
public record RagHit(String id, String text, double score) {
}

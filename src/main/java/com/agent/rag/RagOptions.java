package com.agent.rag;

/**
 * 请求级 RAG 选项：由 {@code conversation.rag().maxResults(n)...} 生成，
 * 经 InterceptorContext 传递给 {@code RagInterceptor}，覆盖全局默认。
 */
public record RagOptions(Integer maxResults, Double minScore) {

    public static RagOptions DEFAULT = new RagOptions(null, null);

    public Integer maxResultsOr(Integer fallback) {
        return maxResults != null && maxResults > 0 ? maxResults : fallback;
    }

    public Double minScoreOr(double fallback) {
        return minScore != null ? minScore : fallback;
    }
}

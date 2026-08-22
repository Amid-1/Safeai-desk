package ru.safeai.gateway.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.knowledge.retrieval")
public record KnowledgeRetrievalProperties(
        Integer maxQueryChars,
        Integer maxTopK,
        Integer candidateLimit,
        Integer rrfK
) {

    public KnowledgeRetrievalProperties {
        maxQueryChars = valueOrDefault(maxQueryChars, 4_000);
        maxTopK = valueOrDefault(maxTopK, 20);
        candidateLimit = valueOrDefault(candidateLimit, 100);
        rrfK = valueOrDefault(rrfK, 60);

        if (maxQueryChars < 1 || maxQueryChars > 20_000) {
            throw invalid("max-query-chars");
        }
        if (maxTopK < 1 || maxTopK > 100) {
            throw invalid("max-top-k");
        }
        if (candidateLimit < maxTopK || candidateLimit > 1_000) {
            throw invalid("candidate-limit");
        }
        if (rrfK < 1 || rrfK > 1_000) {
            throw invalid("rrf-k");
        }
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static IllegalStateException invalid(String property) {
        return new IllegalStateException(
                "Некорректное значение safeai.knowledge.retrieval."
                        + property
        );
    }
}

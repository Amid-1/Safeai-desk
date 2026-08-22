package ru.safeai.gateway.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.knowledge.rag")
public record KnowledgeRagProperties(
        Integer topK,
        Integer maxContextChars,
        Integer maxChunkChars
) {
    public KnowledgeRagProperties {
        topK = bounded(topK, 8, 1, 20, "top-k");
        maxContextChars = bounded(
                maxContextChars,
                24_000,
                1_000,
                100_000,
                "max-context-chars"
        );
        maxChunkChars = bounded(
                maxChunkChars,
                6_000,
                200,
                20_000,
                "max-chunk-chars"
        );
        if (maxChunkChars > maxContextChars) {
            throw invalid("max-chunk-chars");
        }
    }

    private static int bounded(
            Integer value,
            int defaultValue,
            int minimum,
            int maximum,
            String name
    ) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < minimum || resolved > maximum) {
            throw invalid(name);
        }
        return resolved;
    }

    private static IllegalStateException invalid(String property) {
        return new IllegalStateException(
                "Некорректное значение safeai.knowledge.rag." + property
        );
    }
}

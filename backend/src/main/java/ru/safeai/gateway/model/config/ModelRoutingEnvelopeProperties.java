package ru.safeai.gateway.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded upper envelopes for post-reservation input that can be materialized
 * before provider I/O.
 *
 * <p>These are governance ceilings, not target sizes. Actual RAG/system/tool
 * context must remain at or below the reserved envelope and is re-checked
 * immediately before provider execution.</p>
 */
@ConfigurationProperties(prefix = "safeai.model-routing.envelope")
public record ModelRoutingEnvelopeProperties(
        Long systemAndDeveloperTokens,
        Long ragContextTokens,
        Long toolSchemaTokens
) {
    private static final long DEFAULT_SYSTEM_AND_DEVELOPER_TOKENS = 8_192L;
    private static final long DEFAULT_RAG_CONTEXT_TOKENS = 32_768L;
    private static final long DEFAULT_TOOL_SCHEMA_TOKENS = 16_384L;
    private static final long MAX_COMPONENT_TOKENS = 1_000_000L;

    public ModelRoutingEnvelopeProperties {
        systemAndDeveloperTokens = normalize(
                systemAndDeveloperTokens,
                DEFAULT_SYSTEM_AND_DEVELOPER_TOKENS,
                "system-and-developer-tokens"
        );
        ragContextTokens = normalize(
                ragContextTokens,
                DEFAULT_RAG_CONTEXT_TOKENS,
                "rag-context-tokens"
        );
        toolSchemaTokens = normalize(
                toolSchemaTokens,
                DEFAULT_TOOL_SCHEMA_TOKENS,
                "tool-schema-tokens"
        );
    }

    private static long normalize(
            Long value,
            long defaultValue,
            String propertyName
    ) {
        long effective = value == null
                ? defaultValue
                : value;

        if (effective < 0L || effective > MAX_COMPONENT_TOKENS) {
            throw new IllegalStateException(
                    "safeai.model-routing.envelope."
                            + propertyName
                            + " должен быть в диапазоне 0–"
                            + MAX_COMPONENT_TOKENS
            );
        }

        return effective;
    }
}

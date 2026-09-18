package ru.safeai.gateway.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * Bounded upper envelopes for materialized provider input.
 *
 * <p>V48 uses input-unit terminology because the accounting implementation is
 * deliberately tokenizer-independent.</p>
 *
 * <p>Legacy {@code *-tokens} configuration properties remain accepted as
 * aliases during the controlled rollout.</p>
 */
@ConfigurationProperties(prefix = "safeai.model-routing.envelope")
public record ModelRoutingEnvelopeProperties(
        Long systemAndDeveloperInputUnits,
        Long ragContextInputUnits,
        Long toolSchemaInputUnits,
        Long systemAndDeveloperTokens,
        Long ragContextTokens,
        Long toolSchemaTokens
) {
    private static final long DEFAULT_SYSTEM_AND_DEVELOPER_INPUT_UNITS = 8_192L;
    private static final long DEFAULT_RAG_CONTEXT_INPUT_UNITS = 32_768L;
    private static final long DEFAULT_TOOL_SCHEMA_INPUT_UNITS = 16_384L;
    private static final long MAX_COMPONENT_INPUT_UNITS = 1_000_000L;

    public ModelRoutingEnvelopeProperties {
        long system = resolve(
                systemAndDeveloperInputUnits,
                systemAndDeveloperTokens,
                DEFAULT_SYSTEM_AND_DEVELOPER_INPUT_UNITS,
                "system-and-developer-input-units",
                "system-and-developer-tokens"
        );
        long rag = resolve(
                ragContextInputUnits,
                ragContextTokens,
                DEFAULT_RAG_CONTEXT_INPUT_UNITS,
                "rag-context-input-units",
                "rag-context-tokens"
        );
        long tools = resolve(
                toolSchemaInputUnits,
                toolSchemaTokens,
                DEFAULT_TOOL_SCHEMA_INPUT_UNITS,
                "tool-schema-input-units",
                "tool-schema-tokens"
        );

        systemAndDeveloperInputUnits = system;
        ragContextInputUnits = rag;
        toolSchemaInputUnits = tools;
        systemAndDeveloperTokens = system;
        ragContextTokens = rag;
        toolSchemaTokens = tools;
    }

    private static long resolve(
            Long preferred,
            Long legacy,
            long defaultValue,
            String preferredName,
            String legacyName
    ) {
        if (preferred != null
                && legacy != null
                && !Objects.equals(preferred, legacy)) {
            throw new IllegalStateException(
                    "safeai.model-routing.envelope."
                            + preferredName
                            + " и legacy "
                            + legacyName
                            + " заданы одновременно с разными значениями"
            );
        }

        long effective = preferred != null
                ? preferred
                : legacy != null
                ? legacy
                : defaultValue;

        if (effective < 0L || effective > MAX_COMPONENT_INPUT_UNITS) {
            throw new IllegalStateException(
                    "safeai.model-routing.envelope."
                            + preferredName
                            + " должен быть в диапазоне 0–"
                            + MAX_COMPONENT_INPUT_UNITS
            );
        }

        return effective;
    }
}

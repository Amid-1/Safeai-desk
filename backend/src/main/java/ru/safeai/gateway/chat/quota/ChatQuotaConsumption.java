package ru.safeai.gateway.chat.quota;

import java.math.BigDecimal;
import java.util.Objects;

public record ChatQuotaConsumption(
        long requests,
        long inputTokens,
        long outputTokens,
        BigDecimal costUsd,
        boolean usageUnverifiable,
        boolean costUnverifiable
) {
    /**
     * Backward-compatible constructor for existing aggregate readers that do
     * not yet expose data-quality flags.
     *
     * <p>It preserves the previous quota-consumption semantics exactly. New
     * readers that can prove unverifiable usage/cost should use the canonical
     * six-argument constructor explicitly.</p>
     */
    public ChatQuotaConsumption(
            long requests,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd
    ) {
        this(
                requests,
                inputTokens,
                outputTokens,
                costUsd,
                false,
                false
        );
    }

    public ChatQuotaConsumption {
        if (requests < 0
                || inputTokens < 0
                || outputTokens < 0) {

            throw new IllegalArgumentException(
                    "Quota consumption counters не могут быть отрицательными"
            );
        }

        Objects.requireNonNull(
                costUsd,
                "costUsd не должен быть null"
        );

        if (costUsd.signum() < 0) {
            throw new IllegalArgumentException(
                    "costUsd не может быть отрицательным"
            );
        }
    }
}

package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.domain.ModelRouteDecision;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Canonical SHA-256 sealing and verification for immutable V45 route evidence.
 *
 * <p>The canonical field order intentionally matches the original V45
 * {@code ModelRouteDecisionFactory} implementation so already-persisted V45
 * decisions remain verifiable.</p>
 */
final class ModelRouteDecisionIntegrity {

    private ModelRouteDecisionIntegrity() {
    }

    /**
     * PostgreSQL timestamptz stores microsecond precision. New evidence is
     * canonicalized before hashing so a database round-trip cannot change the
     * canonical createdAt representation.
     */
    static Instant normalizeDatabaseTimestamp(
            Instant value
    ) {
        return Objects.requireNonNull(
                value,
                "timestamp не должен быть null"
        ).truncatedTo(ChronoUnit.MICROS);
    }

    static ModelRouteDecision seal(
            ModelRouteDecision decision
    ) {
        Objects.requireNonNull(
                decision,
                "decision не должен быть null"
        );

        return copyWithHash(
                decision,
                calculateSha256(decision)
        );
    }

    static void requireValid(
            ModelRouteDecision decision
    ) {
        Objects.requireNonNull(
                decision,
                "decision не должен быть null"
        );

        String persisted = decision.decisionSha256();
        if (persisted == null
                || !persisted.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "Model route decision содержит некорректный decisionSha256: "
                            + decision.id()
            );
        }

        boolean freeZeroEstimate =
                decision.estimatedMaxCostUsd() != null
                        && decision.estimatedMaxCostUsd().signum() == 0;

        if (hashMatches(
                persisted,
                calculateSha256(decision)
        )) {
            return;
        }

        /*
         * Early V45 builds hashed FREE estimated cost as BigDecimal.ZERO
         * ("0") before PostgreSQL coerced numeric(30,12) to
         * "0.000000000000". Keep that known source-compatible shape
         * verifiable.
         */
        if (freeZeroEstimate
                && hashMatches(
                persisted,
                calculateSha256(
                        decision,
                        true
                )
        )) {
            return;
        }

        /*
         * Early V45 also hashed Clock.instant() before PostgreSQL reduced
         * timestamptz to microsecond precision. The discarded sub-microsecond
         * residue is not stored, so verification tries the narrow legacy
         * neighbourhood only after the exact canonical checks fail. New
         * decisions never need this branch because buildDecision() truncates
         * createdAt before sealing.
         */
        if (matchesLegacyTimestampPrecision(
                decision,
                persisted,
                false
        )) {
            return;
        }
        if (freeZeroEstimate
                && matchesLegacyTimestampPrecision(
                decision,
                persisted,
                true
        )) {
            return;
        }

        throw new IllegalStateException(
                "Model route decision integrity check failed: "
                        + decision.id()
        );
    }

    static String calculateSha256(
            ModelRouteDecision decision
    ) {
        return calculateSha256(
                decision,
                false
        );
    }

    private static String calculateSha256(
            ModelRouteDecision decision,
            boolean legacyFreeZeroEstimate
    ) {
        String capabilities = decision.requiredCapabilities()
                .stream()
                .map(Enum::name)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");

        String canonical = String.join(
                "\n",
                "decisionId=" + decision.id(),
                "organizationId=" + decision.organizationId(),
                "userId=" + decision.userId(),
                "chatId=" + decision.chatId(),
                "chatTurnId=" + nullable(decision.chatTurnId()),
                "clientRequestId=" + decision.clientRequestId(),
                "requestContentHash=" + decision.requestContentHash(),
                "requestedModelKey=" + nullable(
                        decision.requestedModelKey()
                ),
                "catalogEntryId=" + nullable(
                        decision.selectedCatalogEntryId()
                ),
                "catalogVersion=" + nullable(
                        decision.selectedCatalogVersion()
                ),
                "selectedModelKey=" + nullable(
                        decision.selectedModelKey()
                ),
                "provider=" + nullable(
                        decision.selectedProvider()
                ),
                "providerModelId=" + nullable(
                        decision.selectedProviderModelId()
                ),
                "policyId=" + nullable(decision.policyId()),
                "policyVersion=" + nullable(decision.policyVersion()),
                "capabilities=" + capabilities,
                "estimatedInputTokens=" + nullable(
                        decision.estimatedInputTokens()
                ),
                "estimatedOutputTokens=" + nullable(
                        decision.estimatedOutputTokens()
                ),
                "estimatedMaxCostUsd=" + decimal(
                        legacyFreeZeroEstimate
                                && decision.estimatedMaxCostUsd() != null
                                && decision.estimatedMaxCostUsd().signum() == 0
                                ? BigDecimal.ZERO
                                : decision.estimatedMaxCostUsd()
                ),
                "monthlyBudgetUsd=" + decimal(
                        decision.monthlyBudgetUsd()
                ),
                "monthlySpentUsd=" + decimal(
                        decision.monthlySpentUsd()
                ),
                "monthlyProjectedUsd=" + decimal(
                        decision.monthlyProjectedUsd()
                ),
                "monthlyCostKnown=" + decision.monthlyCostKnown(),
                "budgetEnforcement=" + nullable(
                        decision.budgetEnforcement()
                ),
                "budgetExceeded=" + decision.budgetExceeded(),
                "pricingComplete=" + decision.pricingComplete(),
                "outcome=" + decision.outcome(),
                "reason=" + decision.reason(),
                "createdAt=" + decision.createdAt()
        );

        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                            canonical.getBytes(StandardCharsets.UTF_8)
                    );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    private static boolean matchesLegacyTimestampPrecision(
            ModelRouteDecision decision,
            String persisted,
            boolean legacyFreeZeroEstimate
    ) {
        Instant stored = decision.createdAt();
        if (stored == null || stored.getNano() % 1_000 != 0) {
            return false;
        }

        for (int offsetNanos = -999; offsetNanos <= 999; offsetNanos++) {
            if (offsetNanos == 0) {
                continue;
            }

            ModelRouteDecision candidate = copyWithCreatedAt(
                    decision,
                    stored.plusNanos(offsetNanos)
            );
            if (hashMatches(
                    persisted,
                    calculateSha256(
                            candidate,
                            legacyFreeZeroEstimate
                    )
            )) {
                return true;
            }
        }

        return false;
    }

    private static boolean hashMatches(
            String persisted,
            String calculated
    ) {
        return MessageDigest.isEqual(
                calculated.getBytes(StandardCharsets.US_ASCII),
                persisted.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static ModelRouteDecision copyWithHash(
            ModelRouteDecision decision,
            String decisionSha256
    ) {
        return new ModelRouteDecision(
                decision.id(),
                decision.organizationId(),
                decision.userId(),
                decision.chatId(),
                decision.chatTurnId(),
                decision.clientRequestId(),
                decision.requestContentHash(),
                decision.requestedModelKey(),
                decision.selectedCatalogEntryId(),
                decision.selectedCatalogVersion(),
                decision.selectedModelKey(),
                decision.selectedProvider(),
                decision.selectedProviderModelId(),
                decision.policyId(),
                decision.policyVersion(),
                decision.requiredCapabilities(),
                decision.estimatedInputTokens(),
                decision.estimatedOutputTokens(),
                decision.estimatedMaxCostUsd(),
                decision.monthlyBudgetUsd(),
                decision.monthlySpentUsd(),
                decision.monthlyProjectedUsd(),
                decision.monthlyCostKnown(),
                decision.budgetEnforcement(),
                decision.budgetExceeded(),
                decision.pricingComplete(),
                decision.outcome(),
                decision.reason(),
                decisionSha256,
                decision.createdAt()
        );
    }

    private static ModelRouteDecision copyWithCreatedAt(
            ModelRouteDecision decision,
            Instant createdAt
    ) {
        return new ModelRouteDecision(
                decision.id(),
                decision.organizationId(),
                decision.userId(),
                decision.chatId(),
                decision.chatTurnId(),
                decision.clientRequestId(),
                decision.requestContentHash(),
                decision.requestedModelKey(),
                decision.selectedCatalogEntryId(),
                decision.selectedCatalogVersion(),
                decision.selectedModelKey(),
                decision.selectedProvider(),
                decision.selectedProviderModelId(),
                decision.policyId(),
                decision.policyVersion(),
                decision.requiredCapabilities(),
                decision.estimatedInputTokens(),
                decision.estimatedOutputTokens(),
                decision.estimatedMaxCostUsd(),
                decision.monthlyBudgetUsd(),
                decision.monthlySpentUsd(),
                decision.monthlyProjectedUsd(),
                decision.monthlyCostKnown(),
                decision.budgetEnforcement(),
                decision.budgetExceeded(),
                decision.pricingComplete(),
                decision.outcome(),
                decision.reason(),
                decision.decisionSha256(),
                createdAt
        );
    }

    private static String nullable(
            Object value
    ) {
        return value == null
                ? "<null>"
                : value.toString();
    }

    private static String decimal(
            BigDecimal value
    ) {
        return value == null
                ? "<null>"
                : value.toPlainString();
    }
}

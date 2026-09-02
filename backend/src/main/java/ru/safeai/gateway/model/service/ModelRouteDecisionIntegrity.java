package ru.safeai.gateway.model.service;

import ru.safeai.gateway.model.domain.ModelRouteDecision;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned SHA-256 sealing/verification for immutable route evidence. */
final class ModelRouteDecisionIntegrity {

    private static final short V1 = 1;
    private static final short V2 = 2;

    private ModelRouteDecisionIntegrity() {
    }

    static Instant normalizeDatabaseTimestamp(Instant value) {
        return Objects.requireNonNull(value, "timestamp не должен быть null")
                .truncatedTo(ChronoUnit.MICROS);
    }

    static ModelRouteDecision seal(ModelRouteDecision decision) {
        Objects.requireNonNull(decision, "decision не должен быть null");
        if (decision.decisionIntegrityVersion() != V2) {
            throw new IllegalArgumentException(
                    "New route decision must use integrity version 2"
            );
        }
        return copyWithHash(decision, calculateSha256(decision));
    }

    static void requireValid(ModelRouteDecision decision) {
        Objects.requireNonNull(decision, "decision не должен быть null");

        String persisted = decision.decisionSha256();
        if (persisted == null || !persisted.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "Model route decision содержит некорректный decisionSha256: "
                            + decision.id()
            );
        }

        switch (decision.decisionIntegrityVersion()) {
            case V1 -> requireValidV1(decision, persisted);
            case V2 -> {
                if (!hashMatches(persisted, calculateV2Sha256(decision))) {
                    throw integrityFailure(decision);
                }
            }
            default -> throw new IllegalStateException(
                    "Unsupported model route decision integrity version: "
                            + decision.decisionIntegrityVersion()
                            + ", decisionId=" + decision.id()
            );
        }
    }

    static String calculateSha256(ModelRouteDecision decision) {
        return switch (decision.decisionIntegrityVersion()) {
            case V1 -> calculateV1Sha256(decision, false);
            case V2 -> calculateV2Sha256(decision);
            default -> throw new IllegalArgumentException(
                    "Unsupported integrity version: " + decision.decisionIntegrityVersion()
            );
        };
    }

    private static void requireValidV1(ModelRouteDecision decision, String persisted) {
        boolean freeZeroEstimate = decision.estimatedMaxCostUsd() != null
                && decision.estimatedMaxCostUsd().signum() == 0;

        if (hashMatches(persisted, calculateV1Sha256(decision, false))) {
            return;
        }

        // Compatibility with early V45 FREE zero before numeric(30,12) coercion.
        if (freeZeroEstimate
                && hashMatches(persisted, calculateV1Sha256(decision, true))) {
            return;
        }

        // Compatibility with early V45 sub-microsecond Clock.instant() hashing.
        if (matchesLegacyTimestampPrecision(decision, persisted, false)) {
            return;
        }
        if (freeZeroEstimate
                && matchesLegacyTimestampPrecision(decision, persisted, true)) {
            return;
        }

        throw integrityFailure(decision);
    }

    /**
     * V2 uses fixed field order plus length-prefixed UTF-8 values. Null is
     * encoded as length -1, so neither newlines nor sentinel-looking strings
     * can collide with another field distribution.
     */
    private static String calculateV2Sha256(ModelRouteDecision decision) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeShort(V2);
                writeValue(out, decision.id());
                writeValue(out, decision.organizationId());
                writeValue(out, decision.userId());
                writeValue(out, decision.chatId());
                writeValue(out, decision.chatTurnId());
                writeValue(out, decision.clientRequestId());
                writeValue(out, decision.requestContentHash());
                writeValue(out, decision.requestedModelKey());
                writeValue(out, decision.selectedCatalogEntryId());
                writeValue(out, decision.selectedCatalogVersion());
                writeValue(out, decision.selectedModelKey());
                writeValue(out, decision.selectedProvider());
                writeValue(out, decision.selectedProviderModelId());
                writeValue(out, decision.policyId());
                writeValue(out, decision.policyVersion());

                String[] capabilities = decision.requiredCapabilities().stream()
                        .map(Enum::name)
                        .sorted()
                        .toArray(String[]::new);
                out.writeInt(capabilities.length);
                for (String capability : capabilities) {
                    writeUtf8(out, capability);
                }

                writeValue(out, decision.estimatedInputTokens());
                writeValue(out, decision.estimatedOutputTokens());
                writeDecimal(out, decision.estimatedMaxCostUsd());
                writeDecimal(out, decision.monthlyBudgetUsd());
                writeDecimal(out, decision.monthlySpentUsd());
                writeDecimal(out, decision.monthlyProjectedUsd());
                out.writeBoolean(decision.monthlyCostKnown());
                writeValue(out, decision.budgetEnforcement());
                out.writeBoolean(decision.budgetExceeded());
                out.writeBoolean(decision.pricingComplete());
                writeValue(out, decision.outcome());
                writeValue(out, decision.reason());
                writeValue(out, decision.createdAt());
            }
            return sha256(bytes.toByteArray());
        } catch (IOException exception) {
            // ByteArrayOutputStream/DataOutputStream should not fail in-memory.
            throw new IllegalStateException("Cannot build route decision canonical form", exception);
        }
    }

    /** Exact V45 canonical representation, kept only for persisted v1 rows. */
    private static String calculateV1Sha256(
            ModelRouteDecision decision,
            boolean legacyFreeZeroEstimate
    ) {
        String capabilities = decision.requiredCapabilities().stream()
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
                "requestedModelKey=" + nullable(decision.requestedModelKey()),
                "catalogEntryId=" + nullable(decision.selectedCatalogEntryId()),
                "catalogVersion=" + nullable(decision.selectedCatalogVersion()),
                "selectedModelKey=" + nullable(decision.selectedModelKey()),
                "provider=" + nullable(decision.selectedProvider()),
                "providerModelId=" + nullable(decision.selectedProviderModelId()),
                "policyId=" + nullable(decision.policyId()),
                "policyVersion=" + nullable(decision.policyVersion()),
                "capabilities=" + capabilities,
                "estimatedInputTokens=" + nullable(decision.estimatedInputTokens()),
                "estimatedOutputTokens=" + nullable(decision.estimatedOutputTokens()),
                "estimatedMaxCostUsd=" + decimal(
                        legacyFreeZeroEstimate
                                && decision.estimatedMaxCostUsd() != null
                                && decision.estimatedMaxCostUsd().signum() == 0
                                ? BigDecimal.ZERO
                                : decision.estimatedMaxCostUsd()
                ),
                "monthlyBudgetUsd=" + decimal(decision.monthlyBudgetUsd()),
                "monthlySpentUsd=" + decimal(decision.monthlySpentUsd()),
                "monthlyProjectedUsd=" + decimal(decision.monthlyProjectedUsd()),
                "monthlyCostKnown=" + decision.monthlyCostKnown(),
                "budgetEnforcement=" + nullable(decision.budgetEnforcement()),
                "budgetExceeded=" + decision.budgetExceeded(),
                "pricingComplete=" + decision.pricingComplete(),
                "outcome=" + decision.outcome(),
                "reason=" + decision.reason(),
                "createdAt=" + decision.createdAt()
        );

        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
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
                    calculateV1Sha256(candidate, legacyFreeZeroEstimate)
            )) {
                return true;
            }
        }
        return false;
    }

    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        writeUtf8(out, value == null ? null : value.toString());
    }

    private static void writeDecimal(DataOutputStream out, BigDecimal value) throws IOException {
        writeUtf8(out, value == null ? null : value.toPlainString());
    }

    private static void writeUtf8(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean hashMatches(String persisted, String calculated) {
        return MessageDigest.isEqual(
                calculated.getBytes(StandardCharsets.US_ASCII),
                persisted.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static IllegalStateException integrityFailure(ModelRouteDecision decision) {
        return new IllegalStateException(
                "Model route decision integrity check failed: " + decision.id()
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
                decision.decisionIntegrityVersion(),
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
                decision.decisionIntegrityVersion(),
                decision.decisionSha256(),
                createdAt
        );
    }

    private static String nullable(Object value) {
        return value == null ? "<null>" : value.toString();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "<null>" : value.toPlainString();
    }
}

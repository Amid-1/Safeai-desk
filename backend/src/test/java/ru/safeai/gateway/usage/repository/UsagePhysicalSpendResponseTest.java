package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.usage.dto.UsagePhysicalSpendResponse;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsagePhysicalSpendResponseTest {
    private static final Instant FROM = Instant.parse("2026-10-16T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-17T00:00:00Z");

    @Test
    void incompleteCostAndUsageAreNeverReportedAsComplete() {
        UsagePhysicalSpendResponse value = response(1, 1, BigDecimal.ZERO);
        assertThat(value.costComplete()).isFalse();
        assertThat(value.usageComplete()).isFalse();
        assertThat(value.knownCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(value.physicalAttempts()).isEqualTo(2);
    }

    @Test
    void completeFlagsAreDerivedFromEvidenceNotCallerProvidedFlags() {
        UsagePhysicalSpendResponse value = response(0, 0, new BigDecimal("0.020"));
        assertThat(value.costComplete()).isTrue();
        assertThat(value.usageComplete()).isTrue();
    }

    @Test
    void canonicalConstructorRejectsCallerThatMisrepresentsIncompleteEvidence() {
        assertThatThrownBy(() -> new UsagePhysicalSpendResponse(
                FROM, TO, null, "PHYSICAL_ATTEMPT_STARTED_AT", "USD",
                2, 1, 0, 1, 0, 0,
                1, 1, 10, 12, BigDecimal.ZERO, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("costComplete");
    }

    @Test
    void canonicalConstructorRejectsFalseIncompleteFlagForCompleteEvidence() {
        assertThatThrownBy(() -> new UsagePhysicalSpendResponse(
                FROM, TO, null, "PHYSICAL_ATTEMPT_STARTED_AT", "USD",
                2, 0, 0, 2, 0, 0,
                0, 0, 10, 12, BigDecimal.ZERO, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("costComplete");
    }

    @Test
    void rejectsNegativeKnownPhysicalCost() {
        assertThatThrownBy(() -> response(0, 0, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UsagePhysicalSpendResponse response(long costUnknown, long usageUnknown,
                                                        BigDecimal knownCost) {
        return new UsagePhysicalSpendResponse(
                FROM, TO, null, "PHYSICAL_ATTEMPT_STARTED_AT", "USD",
                2, costUnknown, 0, 2 - costUnknown, 0, 0,
                costUnknown, usageUnknown, 10, 12, knownCost);
    }
}

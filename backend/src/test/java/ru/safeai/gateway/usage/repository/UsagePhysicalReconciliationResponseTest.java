package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.usage.dto.UsagePhysicalReconciliationResponse;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsagePhysicalReconciliationResponseTest {
    private static final Instant FROM = Instant.parse("2026-10-16T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-17T00:00:00Z");
    private static final String BASIS = "PLANS_WITH_PHYSICAL_START_IN_RANGE";

    @Test
    void emptyPopulationIsNotAdvertisedAsReconciled() {
        var response = new UsagePhysicalReconciliationResponse(
                FROM, TO, null, BASIS, 0, 0, 0, 0);
        assertThat(response.fullyReconciled()).isFalse();
    }

    @Test
    void mismatchedOrUnknownPlanAlwaysPreventsCompleteReconciliation() {
        var response = new UsagePhysicalReconciliationResponse(
                FROM, TO, null, BASIS, 3, 1, 1, 1);
        assertThat(response.fullyReconciled()).isFalse();
    }

    @Test
    void allMatchedPlansMayBeComplete() {
        var response = new UsagePhysicalReconciliationResponse(
                FROM, TO, null, BASIS, 2, 2, 0, 0);
        assertThat(response.fullyReconciled()).isTrue();
    }

    @Test
    void canonicalConstructorRejectsFalseReconciledEvidence() {
        assertThatThrownBy(() -> new UsagePhysicalReconciliationResponse(
                FROM, TO, null, BASIS, 3, 1, 1, 1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fullyReconciled");
    }

    @Test
    void canonicalConstructorRejectsUnderreportedCompleteness() {
        assertThatThrownBy(() -> new UsagePhysicalReconciliationResponse(
                FROM, TO, null, BASIS, 2, 2, 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fullyReconciled");
    }

    @Test
    void classifiedCountMustMatchTotal() {
        assertThatThrownBy(() -> new UsagePhysicalReconciliationResponse(
                FROM, TO, null, BASIS, 2, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

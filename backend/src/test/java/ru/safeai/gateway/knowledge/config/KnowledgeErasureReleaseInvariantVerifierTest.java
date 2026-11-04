package ru.safeai.gateway.knowledge.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверяет, что отсутствие допуска не включает разрушительные функции. */
class KnowledgeErasureReleaseInvariantVerifierTest {

    @Test
    void disabledDeletionNeedsNoReleaseApproval() {
        assertThatCode(() -> KnowledgeErasureReleaseInvariantVerifier.assertReleaseReady(
                false, false, false, false, false, false, false, false, ""))
                .doesNotThrowAnyException();
    }

    @Test
    void anyMissingApprovalBlocksProductionActivation() {
        boolean[] approved = {true, true, true, true, true, true, true};
        for (int missing = 0; missing < approved.length; missing++) {
            boolean[] attempt = approved.clone();
            attempt[missing] = false;
            assertThatThrownBy(() -> KnowledgeErasureReleaseInvariantVerifier.assertReleaseReady(
                    true, attempt[0], attempt[1], attempt[2], attempt[3],
                    attempt[4], attempt[5], attempt[6], "SEC-RELEASE-2026-001"))
                    .isExactlyInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void missingTicketBlocksActivation() {
        assertThatThrownBy(() -> KnowledgeErasureReleaseInvariantVerifier.assertReleaseReady(
                true, true, true, true, true, true, true, true, ""))
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void completeExplicitApprovalPermitsFurtherRuntimeValidation() {
        assertThatCode(() -> KnowledgeErasureReleaseInvariantVerifier.assertReleaseReady(
                true, true, true, true, true, true, true, true,
                "SEC-RELEASE-2026-001"))
                .doesNotThrowAnyException();
    }
}

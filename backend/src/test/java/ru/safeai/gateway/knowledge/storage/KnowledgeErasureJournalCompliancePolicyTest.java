package ru.safeai.gateway.knowledge.storage;

import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Проверяет строгую политику WORM Compliance без настоящих S3-ключей.
 * Отрицательные случаи доказывают отказ без ослабления самой проверки.
 */
class KnowledgeErasureJournalCompliancePolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final Duration MINIMUM = Duration.ofDays(30);

    @Test
    void acceptsComplianceWithAdditionalSafetyMargin() {
        Retention safe = retention(RetentionMode.COMPLIANCE, NOW.plus(Duration.ofDays(31)));
        assertThat(KnowledgeErasureTombstoneJournal
                .hasSufficientComplianceRetention(safe, MINIMUM, NOW)).isTrue();
        assertDoesNotThrow(() -> KnowledgeErasureTombstoneJournal
                .verifyComplianceRetention(safe, MINIMUM, NOW));
    }

    @Test
    void rejectsGovernanceEvenWithLongerRetention() {
        Retention governance = retention(RetentionMode.GOVERNANCE, NOW.plus(Duration.ofDays(365)));
        assertRejected(governance);
    }

    @Test
    void rejectsMissingRetentionInformation() {
        assertRejected(null);
    }

    @Test
    void rejectsExpiredOrInsufficientRetention() {
        for (int days : new int[]{-1, 0, 1, 29}) {
            assertRejected(retention(RetentionMode.COMPLIANCE,
                    NOW.plus(Duration.ofDays(days))));
        }
    }

    @Test
    void rejectsExactBoundaryAndAcceptsStrictlyLaterTime() {
        Retention exact = retention(RetentionMode.COMPLIANCE, NOW.plus(MINIMUM));
        assertRejected(exact);

        Retention withMargin = retention(RetentionMode.COMPLIANCE,
                NOW.plus(MINIMUM).plusNanos(1));
        assertThat(KnowledgeErasureTombstoneJournal
                .hasSufficientComplianceRetention(withMargin, MINIMUM, NOW)).isTrue();
        assertDoesNotThrow(() -> KnowledgeErasureTombstoneJournal
                .verifyComplianceRetention(withMargin, MINIMUM, NOW));
    }

    @Test
    void rejectsInvalidRetentionPolicyParameters() {
        Retention safe = retention(RetentionMode.COMPLIANCE, NOW.plus(Duration.ofDays(31)));
        assertThrowsExactly(IllegalArgumentException.class, () ->
                KnowledgeErasureTombstoneJournal.hasSufficientComplianceRetention(
                        safe, Duration.ZERO, NOW));
        assertThrowsExactly(IllegalArgumentException.class, () ->
                KnowledgeErasureTombstoneJournal.hasSufficientComplianceRetention(
                        safe, Duration.ofSeconds(-1), NOW));
        assertThrowsExactly(NullPointerException.class, () ->
                KnowledgeErasureTombstoneJournal.hasSufficientComplianceRetention(
                        safe, MINIMUM, null));
    }

    private static void assertRejected(Retention candidate) {
        assertThat(KnowledgeErasureTombstoneJournal
                .hasSufficientComplianceRetention(candidate, MINIMUM, NOW))
                .as("Недопустимая защита S3 не должна разрешать публикацию доказательства")
                .isFalse();
        IOException failure = assertThrowsExactly(IOException.class, () ->
                KnowledgeErasureTombstoneJournal.verifyComplianceRetention(
                        candidate, MINIMUM, NOW));
        assertThat(failure).hasMessageContaining("COMPLIANCE");
    }

    private static Retention retention(RetentionMode mode, Instant until) {
        return new Retention(mode, until.atZone(ZoneOffset.UTC));
    }
}

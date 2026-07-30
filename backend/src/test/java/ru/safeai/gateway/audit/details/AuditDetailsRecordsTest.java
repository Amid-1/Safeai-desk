package ru.safeai.gateway.audit.details;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditDetailsRecordsTest {

    @Test
    void aiResponseDetailsExposeOnlyStructuredMetadata() {
        UUID chatId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();

        AiResponseAuditDetails details =
                new AiResponseAuditDetails(
                        chatId,
                        messageId,
                        userMessageId,
                        " gpt-test ",
                        10,
                        20,
                        new BigDecimal("0.001"),
                        150L,
                        " provider-message ",
                        " stop ",
                        " COMPLETED ",
                        " AVAILABLE ",
                        " PRICED ",
                        " USD ",
                        " v1 ",
                        Instant.parse(
                                "2026-07-30T08:00:00Z"
                        )
                );

        assertThat(details.toMap())
                .containsEntry("chatId", chatId)
                .containsEntry("messageId", messageId)
                .containsEntry(
                        "userMessageId",
                        userMessageId
                )
                .containsEntry("model", "gpt-test")
                .containsEntry("inputTokens", 10)
                .containsEntry("outputTokens", 20)
                .containsEntry("durationMs", 150L)
                .containsEntry(
                        "aiResponseStatus",
                        "COMPLETED"
                )
                .containsEntry(
                        "pricingVersion",
                        "v1"
                );
    }

    @Test
    void aiResponseDetailsRejectNegativeMetrics() {
        assertThatThrownBy(() ->
                new AiResponseAuditDetails(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "model",
                        -1,
                        1,
                        BigDecimal.ZERO,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining("inputTokens");
    }

    @Test
    void rateLimitDetailsAreCanonicalAndComplete() {
        RateLimitAuditDetails details =
                new RateLimitAuditDetails(
                        " AI_MESSAGE_USER ",
                        100,
                        " 1h ",
                        101
                );

        assertThat(details.toMap())
                .isEqualTo(
                        Map.of(
                                "type",
                                "AI_MESSAGE_USER",
                                "limit",
                                100,
                                "window",
                                "1h",
                                "count",
                                101L
                        )
                );
    }

    @Test
    void refreshReuseDetailsPreserveInvestigationIdentifier() {
        UUID familyId = UUID.randomUUID();

        SecurityRefreshReuseAuditDetails details =
                new SecurityRefreshReuseAuditDetails(
                        familyId,
                        " 127.0.0.1 ",
                        " browser ",
                        " request-1 "
                );

        assertThat(details.toMap())
                .containsEntry(
                        "tokenFamilyId",
                        familyId
                )
                .containsEntry("ip", "127.0.0.1")
                .containsEntry(
                        "userAgent",
                        "browser"
                )
                .containsEntry(
                        "requestId",
                        "request-1"
                );
    }
}

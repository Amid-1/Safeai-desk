package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceededEventPiiTest {
    @Test
    void userEventIsValidWithoutEmail() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        RateLimitExceededEvent event = new RateLimitExceededEvent(
                userId, orgId, null, null, orgId,
                "AI_MESSAGE_USER", 50, "1h", Map.of("decision", "FIRST_EXCEEDED"));
        assertThat(event.actorEmail()).isNull();
        assertThat(event.actorUserId()).isEqualTo(userId);
        assertThat(event.actorOrganizationId()).isEqualTo(orgId);
    }

    @Test
    void anonymousLoginEventCarriesFingerprintWithoutRawEmail() {
        UUID auditOrg = UUID.randomUUID();
        RateLimitExceededEvent event = new RateLimitExceededEvent(
                null, null, null, null, auditOrg,
                "LOGIN_IP", 10, "1m", Map.of("emailFingerprint", "pseudonymous"));
        assertThat(event.actorEmail()).isNull();
        assertThat(event.details()).containsKey("emailFingerprint").doesNotContainKey("email");
    }
}

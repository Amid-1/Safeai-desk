package ru.safeai.gateway.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventClassificationTest {

    @Test
    void everyKnownEventHasOneCategoryAndNoContradictoryOutcome() {
        for (AuditEventType type : AuditEventType.values()) {
            assertThat(AuditEventClassification.category(type)).isNotNull();
            assertThat(AuditEventClassification.matches(type, AuditEventClassification.Metric.SUCCESS)
                    && AuditEventClassification.matches(type, AuditEventClassification.Metric.DENIED)).isFalse();
        }
    }

    @Test
    void criticalMeansSecuritySignalNotGenericFailure() {
        assertThat(AuditEventClassification.types(AuditEventClassification.Metric.CRITICAL))
                .containsExactly("SECURITY_REFRESH_REUSE_DETECTED");
        assertThat(AuditEventClassification.types(AuditEventClassification.Metric.DENIED))
                .containsExactlyInAnyOrder("USER_LOGIN_FAILED", "MODEL_ROUTE_DENIED", "RATE_LIMIT_EXCEEDED");
    }
}

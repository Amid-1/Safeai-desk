package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuditOutboxIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final String COUNT_OUTBOX_SQL =
            "select count(*) from public.audit_outbox";

    private static final String COUNT_EVENTS_SQL =
            "select count(*) from public.audit_events";

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private AuditOutboxProcessor auditOutboxProcessor;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void committedAuditIntentIsMovedFromOutboxToAuditStorage() {
        UUID organizationId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Audit Organization",
                true
        );

        auditEventService.recordSystem(
                organizationId,
                AuditEventType.USER_CREATED,
                Map.of(
                        "targetUserId",
                        UUID.randomUUID().toString()
                )
        );

        assertThat(countAuditOutbox())
                .isEqualTo(1);

        assertThat(countAuditEvents())
                .isZero();

        assertThat(auditOutboxProcessor.processBatch())
                .isEqualTo(1);

        assertThat(countAuditOutbox())
                .isZero();

        assertThat(countAuditEvents())
                .isEqualTo(1);

        assertThat(auditOutboxProcessor.processBatch())
                .isZero();

        assertThat(countAuditEvents())
                .isEqualTo(1);
    }

    @Test
    void rolledBackBusinessTransactionLeavesNoOutboxIntent() {
        UUID organizationId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Rollback Audit Organization",
                true
        );

        TransactionTemplate transaction =
                new TransactionTemplate(
                        transactionManager
                );

        assertThatThrownBy(() ->
                transaction.executeWithoutResult(status -> {
                    auditEventService.recordSystem(
                            organizationId,
                            AuditEventType.USER_CREATED,
                            Map.of("test", true)
                    );

                    throw new IllegalStateException(
                            "rollback"
                    );
                })
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "rollback"
                );

        assertThat(countAuditOutbox())
                .isZero();

        assertThat(countAuditEvents())
                .isZero();
    }

    private int countAuditOutbox() {
        return queryCount(
                COUNT_OUTBOX_SQL
        );
    }

    private int countAuditEvents() {
        return queryCount(
                COUNT_EVENTS_SQL
        );
    }

    private int queryCount(
            String sql
    ) {
        Integer count = jdbcTemplate.queryForObject(
                sql,
                Integer.class
        );

        return count == null
                ? 0
                : count;
    }
}

package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuditOutboxIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private AuditOutboxProcessor auditOutboxProcessor;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void businessTransactionRollbackLeavesNoAuditIntentOrEvent() {
        UUID organizationId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Rollback Audit " + organizationId,
                true
        );

        TransactionTemplate transaction =
                new TransactionTemplate(
                        transactionManager
                );

        assertThatThrownBy(() ->
                transaction.executeWithoutResult(status -> {
                    jdbcTemplate.update(
                            """
                            update public.organizations
                            set name = ?
                            where id = ?
                            """,
                            "Rollback Changed "
                                    + organizationId,
                            organizationId
                    );

                    auditEventService.recordSystem(
                            organizationId,
                            AuditEventType
                                    .ORGANIZATION_NAME_CHANGED,
                            Map.of(
                                    "newName",
                                    "Rollback Changed "
                                            + organizationId
                            )
                    );

                    throw new IllegalStateException(
                            "force rollback"
                    );
                })
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "force rollback"
                );

        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();

        assertThat(
                queryString(
                        """
                        select name
                        from public.organizations
                        where id = ?
                        """,
                        organizationId
                )
        ).startsWith("Rollback Audit");
    }

    @Test
    void businessCommitCreatesDurableIntentAndWorkerCreatesEvent() {
        UUID organizationId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Commit Audit " + organizationId,
                true
        );

        TransactionTemplate transaction =
                new TransactionTemplate(
                        transactionManager
                );

        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    update public.organizations
                    set name = ?
                    where id = ?
                    """,
                    "Committed Audit " + organizationId,
                    organizationId
            );

            auditEventService.recordSystem(
                    organizationId,
                    AuditEventType
                            .ORGANIZATION_NAME_CHANGED,
                    Map.of(
                            "newName",
                            "Committed Audit "
                                    + organizationId
                    )
            );
        });

        assertThat(countAuditOutbox()).isEqualTo(1);
        assertThat(countAuditEvents()).isZero();

        AuditOutboxProcessor.BatchResult result =
                auditOutboxProcessor.processBatch();

        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isEqualTo(1);

        assertThat(
                queryString(
                        """
                        select event_type
                        from public.audit_events
                        """
                )
        ).isEqualTo(
                AuditEventType
                        .ORGANIZATION_NAME_CHANGED
                        .name()
        );
    }

    @Test
    void secondWorkerRunDoesNotCreateDuplicateEvent() {
        UUID organizationId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Idempotent Audit " + organizationId,
                true
        );

        auditEventService.recordSystem(
                organizationId,
                AuditEventType.USER_CREATED,
                Map.of("test", true)
        );

        UUID eventId = queryUuid(
                """
                select id
                from public.audit_outbox
                """
        );

        insertDeliveredAuditEvent(
                eventId,
                organizationId,
                Instant.parse(
                        "2026-07-30T08:00:00Z"
                )
        );

        AuditOutboxProcessor.BatchResult first =
                auditOutboxProcessor.processBatch();

        AuditOutboxProcessor.BatchResult second =
                auditOutboxProcessor.processBatch();

        assertThat(first.processed()).isEqualTo(1);
        assertThat(second.processed()).isZero();
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isEqualTo(1);
    }

    @Test
    void unknownEventTypeIsRejectedAtomicallyByDatabasePolicy() {
        UUID organizationId = UUID.randomUUID();

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        insert into public.audit_outbox (
                            id,
                            organization_id,
                            event_type,
                            details,
                            created_at
                        )
                        values (
                            ?,
                            ?,
                            'UNKNOWN_EVENT_TYPE',
                            '{}'::jsonb,
                            current_timestamp
                        )
                        """,
                        UUID.randomUUID(),
                        organizationId
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );

        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    @Test
    void databaseDetailsSizeConstraintRejectsOversizedRawInsert() {
        String oversized =
                "{\"payload\":\""
                        + "x".repeat(70_000)
                        + "\"}";

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        insert into public.audit_events (
                            id,
                            organization_id,
                            event_type,
                            details,
                            created_at
                        )
                        values (
                            ?,
                            ?,
                            ?,
                            cast(? as jsonb),
                            current_timestamp
                        )
                        """,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        AuditEventType.USER_CREATED.name(),
                        oversized
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );

        assertThat(countAuditEvents()).isZero();
    }

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    private void insertDeliveredAuditEvent(
            UUID eventId,
            UUID organizationId,
            Instant createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into public.audit_events (
                    id,
                    user_id,
                    actor_user_id,
                    actor_organization_id,
                    actor_email,
                    actor_display_name,
                    organization_id,
                    event_type,
                    details,
                    created_at
                )
                values (
                    ?,
                    null,
                    null,
                    null,
                    null,
                    'SYSTEM',
                    ?,
                    ?,
                    cast(? as jsonb),
                    ?
                )
                """,
                eventId,
                organizationId,
                AuditEventType.USER_CREATED.name(),
                """
                {"alreadyDelivered":true}
                """.trim(),
                new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE,
                        createdAt.atOffset(
                                ZoneOffset.UTC
                        )
                )
        );
    }
}
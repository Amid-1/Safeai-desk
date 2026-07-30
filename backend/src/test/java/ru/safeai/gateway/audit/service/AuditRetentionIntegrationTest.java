package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AuditRetentionIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    private static final Instant THRESHOLD =
            Instant.parse(
                    "2026-01-01T00:00:00Z"
            );

    private static final String EVENT_TYPE =
            "USER_CREATED";

    private static final String DETAILS_JSON =
            """
            {"retention":true}
            """.trim();

    @Autowired
    private AuditRetentionBatchService batchService;

    @Autowired
    private JdbcTemplate directJdbcTemplate;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void deletesOnlyEventsStrictlyOlderThanThreshold() {
        UUID organizationId = UUID.randomUUID();

        UUID oldId = UUID.randomUUID();
        UUID boundaryId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();

        insertEvent(
                oldId,
                organizationId,
                THRESHOLD.minus(
                        1,
                        ChronoUnit.MICROS
                )
        );

        insertEvent(
                boundaryId,
                organizationId,
                THRESHOLD
        );

        insertEvent(
                newId,
                organizationId,
                THRESHOLD.plus(
                        1,
                        ChronoUnit.MICROS
                )
        );

        int deleted =
                batchService.deleteBatch(
                        THRESHOLD,
                        100
                );

        assertThat(deleted).isEqualTo(1);

        assertThat(
                queryInt(
                        """
                        select count(*)
                        from public.audit_events
                        where id = ?
                        """,
                        oldId
                )
        ).isZero();

        assertThat(
                queryInt(
                        """
                        select count(*)
                        from public.audit_events
                        where id in (?, ?)
                        """,
                        boundaryId,
                        newId
                )
        ).isEqualTo(2);
    }

    @Test
    void batchSizeIsStrictlyRespected() {
        UUID organizationId = UUID.randomUUID();

        for (int index = 0; index < 5; index++) {
            insertEvent(
                    UUID.randomUUID(),
                    organizationId,
                    THRESHOLD.minusSeconds(
                            10L + index
                    )
            );
        }

        int deleted =
                batchService.deleteBatch(
                        THRESHOLD,
                        2
                );

        assertThat(deleted).isEqualTo(2);
        assertThat(countAuditEvents()).isEqualTo(3);
    }

    @Test
    void repeatedBatchesEventuallyDeleteAllExpiredRowsOnly() {
        UUID organizationId = UUID.randomUUID();

        for (int index = 0; index < 7; index++) {
            insertEvent(
                    UUID.randomUUID(),
                    organizationId,
                    THRESHOLD.minusSeconds(
                            100L + index
                    )
            );
        }

        insertEvent(
                UUID.randomUUID(),
                organizationId,
                THRESHOLD.plusSeconds(1)
        );

        int total = 0;

        while (true) {
            int deleted =
                    batchService.deleteBatch(
                            THRESHOLD,
                            3
                    );

            total += deleted;

            if (deleted < 3) {
                break;
            }
        }

        assertThat(total).isEqualTo(7);
        assertThat(countAuditEvents()).isEqualTo(1);
    }

    @SuppressWarnings({
            "SqlResolve",
            "SqlNoDataSourceInspection"
    })
    private void insertEvent(
            UUID eventId,
            UUID organizationId,
            Instant createdAt
    ) {
        Objects.requireNonNull(
                eventId,
                "eventId не должен быть null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                createdAt,
                "createdAt не должен быть null"
        );

        directJdbcTemplate.update("""
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
                EVENT_TYPE,
                DETAILS_JSON,
                new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE,
                        createdAt.atOffset(
                                ZoneOffset.UTC
                        )
                )
        );
    }
}
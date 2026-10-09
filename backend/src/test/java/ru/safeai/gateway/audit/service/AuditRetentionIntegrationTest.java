package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
class AuditRetentionIntegrationTest extends AbstractAuditPostgresIntegrationTest {

    /* The retention cutoff is based on PostgreSQL time, not a JVM clock. */
    private static final Duration RETENTION_PERIOD = Duration.ofHours(1);
    private static final Duration CUTOFF_MARGIN = Duration.ofMinutes(5);
    private static final String EVENT_TYPE = "USER_CREATED";
    private static final String DETAILS_JSON = """
            {"retention":true}
            """.trim();

    @Autowired
    private AuditRetentionBatchService batchService;

    @Autowired
    private JdbcTemplate directJdbcTemplate;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void deletesOnlyEventsOlderThanDatabaseRetentionCutoff() {
        UUID organizationId = UUID.randomUUID();
        UUID expiredId = UUID.randomUUID();
        UUID retainedNearCutoffId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();
        Instant databaseNow = databaseNow();

        insertEvent(expiredId, organizationId,
                databaseNow.minus(RETENTION_PERIOD).minus(CUTOFF_MARGIN));
        insertEvent(retainedNearCutoffId, organizationId,
                databaseNow.minus(RETENTION_PERIOD).plus(CUTOFF_MARGIN));
        insertEvent(recentId, organizationId, databaseNow);

        int deleted = batchService.deleteBatch(RETENTION_PERIOD, 100);
        assertThat(deleted).isEqualTo(1);
        assertThat(queryInt("""
                select count(*) from public.audit_events where id = ?
                """, expiredId)).isZero();
        assertThat(queryInt("""
                select count(*) from public.audit_events where id in (?, ?)
                """, retainedNearCutoffId, recentId)).isEqualTo(2);
    }

    @Test
    void batchSizeIsStrictlyRespected() {
        UUID organizationId = UUID.randomUUID();
        Instant expiredAt = databaseNow()
                .minus(RETENTION_PERIOD)
                .minus(CUTOFF_MARGIN);

        for (int index = 0; index < 5; index++) {
            insertEvent(UUID.randomUUID(), organizationId,
                    expiredAt.minusSeconds(index));
        }

        int deleted = batchService.deleteBatch(RETENTION_PERIOD, 2);
        assertThat(deleted).isEqualTo(2);
        assertThat(countAuditEvents()).isEqualTo(3);
    }

    @Test
    void repeatedBatchesEventuallyDeleteAllExpiredRowsOnly() {
        UUID organizationId = UUID.randomUUID();
        Instant databaseNow = databaseNow();
        Instant expiredAt = databaseNow
                .minus(RETENTION_PERIOD)
                .minus(CUTOFF_MARGIN);

        for (int index = 0; index < 7; index++) {
            insertEvent(UUID.randomUUID(), organizationId,
                    expiredAt.minusSeconds(index));
        }
        insertEvent(UUID.randomUUID(), organizationId, databaseNow);

        int total = 0;
        while (true) {
            int deleted = batchService.deleteBatch(RETENTION_PERIOD, 3);
            total += deleted;
            if (deleted < 3) {
                break;
            }
        }

        assertThat(total).isEqualTo(7);
        assertThat(countAuditEvents()).isEqualTo(1);
    }

    private Instant databaseNow() {
        Timestamp value = directJdbcTemplate.queryForObject(
                "select clock_timestamp()", Timestamp.class);
        return Objects.requireNonNull(value,
                "PostgreSQL clock_timestamp() вернул null").toInstant();
    }

    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    private void insertEvent(UUID eventId, UUID organizationId, Instant createdAt) {
        Objects.requireNonNull(eventId, "eventId не должен быть null");
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        Objects.requireNonNull(createdAt, "createdAt не должен быть null");

        // V70: audit_events.user_id was removed. SYSTEM events have no live user link.
        directJdbcTemplate.update("""
                insert into public.audit_events (
                    id,
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
                new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE,
                        createdAt.atOffset(ZoneOffset.UTC))
        );
    }
}

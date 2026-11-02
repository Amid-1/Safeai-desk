package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные проверки очистки аудита по сроку хранения.
 *
 * <p>Используется настоящий PostgreSQL. Время отсечения рассчитывается
 * PostgreSQL, а не часами JVM. Все проверки работают с изолированной
 * тестовой базой, которую подготавливает базовый тестовый класс.</p>
 */
@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
class AuditRetentionIntegrationTest extends AbstractAuditPostgresIntegrationTest {

    private static final Duration RETENTION_PERIOD = Duration.ofHours(1);
    private static final Duration CUTOFF_MARGIN = Duration.ofMinutes(5);
    private static final String DETAILS_JSON = "{\"retention\":true}";

    @Autowired
    private AuditRetentionBatchService batchService;

    // Планировщик отключён только в тесте: пакетная очистка вызывается явно.
    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void deletesOnlyEventsOlderThanDatabaseRetentionCutoff() {
        UUID organizationId = UUID.randomUUID();
        UUID expiredId = UUID.randomUUID();
        UUID nearCutoffId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();
        Instant databaseNow = databaseNow();

        insertEvent(expiredId, organizationId,
                databaseNow.minus(RETENTION_PERIOD).minus(CUTOFF_MARGIN));
        insertEvent(nearCutoffId, organizationId,
                databaseNow.minus(RETENTION_PERIOD).plus(CUTOFF_MARGIN));
        insertEvent(recentId, organizationId, databaseNow);

        assertThat(batchService.deleteBatch(RETENTION_PERIOD, 100)).isEqualTo(1);
        assertThat(eventExists(expiredId)).isFalse();
        assertThat(eventExists(nearCutoffId)).isTrue();
        assertThat(eventExists(recentId)).isTrue();
        assertThat(countEventsInOrganization(organizationId)).isEqualTo(2);
    }

    @Test
    void batchSizeIsStrictlyRespected() {
        UUID organizationId = UUID.randomUUID();
        Instant expiredAt = databaseNow()
                .minus(RETENTION_PERIOD).minus(CUTOFF_MARGIN);

        for (int index = 0; index < 5; index++) {
            insertEvent(UUID.randomUUID(), organizationId,
                    expiredAt.minusSeconds(index));
        }

        // Один вызов не должен удалять больше разрешённого размера пакета.
        assertThat(batchService.deleteBatch(RETENTION_PERIOD, 2)).isEqualTo(2);
        assertThat(countEventsInOrganization(organizationId)).isEqualTo(3);
    }

    @Test
    void repeatedBatchesEventuallyDeleteAllExpiredRowsOnly() {
        UUID organizationId = UUID.randomUUID();
        Instant databaseNow = databaseNow();
        Instant expiredAt = databaseNow
                .minus(RETENTION_PERIOD).minus(CUTOFF_MARGIN);
        UUID recentId = UUID.randomUUID();

        for (int index = 0; index < 7; index++) {
            insertEvent(UUID.randomUUID(), organizationId,
                    expiredAt.minusSeconds(index));
        }
        insertEvent(recentId, organizationId, databaseNow);

        // Проверяем точное распределение по пакетам: 3 + 3 + 1 + 0.
        // Конечная последовательность исключает бесконечное ожидание при ошибке worker.
        assertThat(batchService.deleteBatch(RETENTION_PERIOD, 3)).isEqualTo(3);
        assertThat(batchService.deleteBatch(RETENTION_PERIOD, 3)).isEqualTo(3);
        assertThat(batchService.deleteBatch(RETENTION_PERIOD, 3)).isEqualTo(1);
        assertThat(batchService.deleteBatch(RETENTION_PERIOD, 3)).isZero();

        assertThat(countEventsInOrganization(organizationId)).isEqualTo(1);
        assertThat(eventExists(recentId)).isTrue();
    }

    @Test
    void emptyEligibleSetDoesNotDeleteRecentEvidence() {
        UUID organizationId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();
        insertEvent(recentId, organizationId, databaseNow());

        assertThat(batchService.deleteBatch(RETENTION_PERIOD, 10)).isZero();
        assertThat(eventExists(recentId)).isTrue();
    }

    @Test
    void invalidSettingsCannotDeleteAuditEvidence() {
        UUID organizationId = UUID.randomUUID();
        UUID expiredId = UUID.randomUUID();
        insertEvent(expiredId, organizationId,
                databaseNow().minus(Duration.ofDays(2)));

        // Валидация должна отклонять некорректные параметры до SQL DELETE.
        assertThatThrownBy(() -> batchService.deleteBatch(Duration.ZERO, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batchService.deleteBatch(Duration.ofMillis(500), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batchService.deleteBatch(RETENTION_PERIOD, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batchService.deleteBatch(RETENTION_PERIOD, -1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(eventExists(expiredId)).isTrue();
    }

    private Instant databaseNow() {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "select clock_timestamp()", Timestamp.class);
        return Objects.requireNonNull(timestamp,
                "PostgreSQL clock_timestamp() вернул null").toInstant();
    }

    private boolean eventExists(UUID eventId) {
        return queryInt("select count(*) from public.audit_events where id = ?",
                eventId) == 1;
    }

    private int countEventsInOrganization(UUID organizationId) {
        return queryInt("select count(*) from public.audit_events where organization_id = ?",
                organizationId);
    }

    private void insertEvent(UUID eventId, UUID organizationId, Instant createdAt) {
        Objects.requireNonNull(eventId, "eventId не должен быть null");
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        Objects.requireNonNull(createdAt, "createdAt не должен быть null");

        // После V70 аудит хранит неизменяемый снимок автора без старого user_id.
        jdbcTemplate.update("""
                insert into public.audit_events (
                    id, actor_user_id, actor_organization_id,
                    actor_email, actor_display_name, organization_id,
                    event_type, details, created_at
                )
                values (?, null, null, null, 'SYSTEM', ?, ?, cast(? as jsonb), ?)
                """,
                eventId,
                organizationId,
                AuditEventType.USER_CREATED.name(),
                DETAILS_JSON,
                new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE,
                        createdAt.atOffset(ZoneOffset.UTC)));
    }
}

package ru.safeai.gateway.audit.repository;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционная проверка индекса поиска аудита по префиксу адреса автора.
 *
 * <p>Тест использует настоящий PostgreSQL из Testcontainers. Он проверяет не
 * только наличие индекса из миграции V27, но и содержимое результата запроса,
 * изоляцию по организации и план выполнения. Данные аудита не изменяются
 * вне одноразовой тестовой базы.</p>
 */
@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
class AuditEmailPrefixIndexIntegrationTest extends AbstractAuditPostgresIntegrationTest {

    private static final int NOISE_EVENT_COUNT = 2_000;
    private static final Instant BASE_CREATED_AT = Instant.parse("2026-07-30T08:00:00Z");
    private static final String MATCHING_EMAIL = "prefix-admin@test.com";
    private static final String MATCHING_DISPLAY_NAME = "Admin";
    private static final String EMAIL_PREFIX_PATTERN = "prefix%";
    private static final String EXPECTED_INDEX_NAME =
            "idx_audit_events_org_actor_email_pattern_created_id";

    /** Планировщик фоновой доставки отключается только в данном тестовом контексте. */
    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void migrationDefinesOrganizationScopedPatternOpsIndex() {
        // Независимо от EXPLAIN проверяем фактическую структуру индекса в каталоге PostgreSQL.
        String definition = jdbcTemplate.queryForObject("""
                select pg_get_indexdef(indexrelid)
                from pg_index
                where indexrelid = to_regclass(?)
                """, String.class, "public." + EXPECTED_INDEX_NAME);

        assertThat(definition)
                .as("Миграция V27 должна создать индекс поиска по префиксу в рамках организации")
                .isNotBlank()
                .contains("organization_id")
                .contains("actor_email text_pattern_ops")
                .contains("created_at DESC")
                .contains("id DESC")
                .contains("actor_email IS NOT NULL");

        // CREATE INDEX CONCURRENTLY может оставить существующий, но невалидный индекс.
        // Для production важны три состояния: индекс валиден, готов к вставкам и живой.
        Map<String, Object> state = jdbcTemplate.queryForMap("""
                select i.indisvalid as valid,
                       i.indisready as ready,
                       i.indislive as live
                  from pg_catalog.pg_index as i
                 where i.indexrelid = to_regclass(?)
                   and i.indrelid = 'public.audit_events'::regclass
                """, "public." + EXPECTED_INDEX_NAME);

        assertThat(state)
                .as("Индекс должен принадлежать public.audit_events и быть пригодным к использованию")
                .containsEntry("valid", true)
                .containsEntry("ready", true)
                .containsEntry("live", true);
    }

    @Test
    void canonicalEmailPrefixQueryUsesCompositeIndexAndReturnsOnlyMatchingOrganization() {
        UUID organizationId = UUID.randomUUID();
        UUID otherOrganizationId = UUID.randomUUID();
        UUID matchingEventId = UUID.randomUUID();
        UUID otherOrganizationEventId = UUID.randomUUID();

        insertMatchingEvent(matchingEventId, UUID.randomUUID(),
                organizationId, organizationId, Map.of());
        // Аналогичный адрес в другой организации не должен попасть в выдачу.
        insertMatchingEvent(otherOrganizationEventId, UUID.randomUUID(),
                otherOrganizationId, otherOrganizationId, Map.of());
        // Большой объём неподходящих адресов обеспечивает реалистичную проверку индекса.
        insertNoiseEvents(organizationId, BASE_CREATED_AT.plusSeconds(1));
        jdbcTemplate.execute("analyze public.audit_events");

        List<UUID> actualIds = jdbcTemplate.query("""
                select id
                from public.audit_events
                where organization_id = ?
                  and actor_email like ? escape '\\'
                """, (resultSet, rowNumber) ->
                        resultSet.getObject("id", UUID.class),
                organizationId, EMAIL_PREFIX_PATTERN);

        assertThat(actualIds)
                .as("Поиск по префиксу не должен раскрывать аудит другой организации")
                .containsExactly(matchingEventId);

        String plan = explainCanonicalEmailPrefixQuery(organizationId);
        assertThat(plan)
                .as("План должен использовать составной индекс для данной организации")
                .contains(EXPECTED_INDEX_NAME)
                .contains("Index Cond");
    }

    @Test
    void prefixQueryDoesNotReturnNonmatchingEmails() {
        UUID organizationId = UUID.randomUUID();
        UUID matchingEventId = UUID.randomUUID();
        insertMatchingEvent(matchingEventId, UUID.randomUUID(),
                organizationId, organizationId, Map.of("source", "test"));
        insertNoiseEvents(organizationId, BASE_CREATED_AT.plusSeconds(1));

        List<String> emails = jdbcTemplate.query("""
                select actor_email
                from public.audit_events
                where organization_id = ?
                  and actor_email like ? escape '\\'
                """, (resultSet, rowNumber) -> resultSet.getString(1),
                organizationId, EMAIL_PREFIX_PATTERN);

        assertThat(emails).containsExactly(MATCHING_EMAIL);
    }

    /**
     * Проверяет план с принудительно выключенным последовательным сканированием.
     * Настройка SET LOCAL действует только внутри транзакции этого теста и не
     * изменяет производственную конфигурацию PostgreSQL.
     */
    private String explainCanonicalEmailPrefixQuery(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String plan = transaction.execute(status -> {
            jdbcTemplate.execute("set local enable_seqscan = off");
            List<String> lines = jdbcTemplate.query("""
                    explain (costs off)
                    select id, actor_email, created_at
                    from public.audit_events
                    where organization_id = ?
                      and actor_email like ? escape '\\'
                    """, (resultSet, rowNumber) -> resultSet.getString(1),
                    organizationId, EMAIL_PREFIX_PATTERN);
            return String.join(System.lineSeparator(), lines);
        });
        return Objects.requireNonNull(plan, "PostgreSQL не вернул EXPLAIN-план");
    }

    /** Добавляет подтверждённое событие без зависимости от текущего состояния пользователей. */
    private void insertMatchingEvent(UUID eventId, UUID actorUserId,
                                     UUID actorOrganizationId, UUID targetOrganizationId,
                                     Map<String, Object> details) {
        Objects.requireNonNull(eventId, "eventId не должен быть null");
        Objects.requireNonNull(actorUserId, "actorUserId не должен быть null");
        Objects.requireNonNull(actorOrganizationId, "actorOrganizationId не должен быть null");
        Objects.requireNonNull(targetOrganizationId, "targetOrganizationId не должен быть null");
        Objects.requireNonNull(details, "details не должен быть null");

        // Снимок автора не требует наличия живой строки в таблице пользователей.
        jdbcTemplate.update("""
                insert into public.audit_events (
                    id, actor_user_id, actor_organization_id,
                    actor_email, actor_display_name, organization_id,
                    event_type, details, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                eventId, actorUserId, actorOrganizationId,
                MATCHING_EMAIL, MATCHING_DISPLAY_NAME, targetOrganizationId,
                AuditEventType.USER_UPDATED.name(), toJson(details),
                timestampParameter(BASE_CREATED_AT));
    }

    /** Создаёт объёмные контрольные данные с несовпадающими префиксами. */
    private void insertNoiseEvents(UUID organizationId, Instant firstCreatedAt) {
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        Objects.requireNonNull(firstCreatedAt, "firstCreatedAt не должен быть null");
        jdbcTemplate.update("""
                insert into public.audit_events (
                    id, actor_user_id, actor_organization_id,
                    actor_email, actor_display_name, organization_id,
                    event_type, details, created_at
                )
                select
                    gen_random_uuid(),
                    gen_random_uuid(),
                    ?,
                    'noise-' || lpad(series_number::text, 6, '0') || '@test.com',
                    'Noise Actor',
                    ?,
                    ?,
                    '{}'::jsonb,
                    ? + make_interval(secs => series_number)
                from generate_series(1, ?) as series_number
                """,
                organizationId, organizationId,
                AuditEventType.USER_UPDATED.name(),
                timestampParameter(firstCreatedAt), NOISE_EVENT_COUNT);
    }

    /** Сохраняет timestamptz-параметр с однозначной UTC-временной зоной. */
    private SqlParameterValue timestampParameter(Instant instant) {
        Objects.requireNonNull(instant, "instant не должен быть null");
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE,
                instant.atOffset(ZoneOffset.UTC));
    }
}

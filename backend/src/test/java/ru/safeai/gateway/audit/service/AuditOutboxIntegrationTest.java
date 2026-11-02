package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные проверки обязательного транзакционного аудита и его доставки.
 *
 * <p>Используется настоящий PostgreSQL из Testcontainers. Фоновый планировщик
 * подменён только для исключения гонки с ручным вызовом {@code processBatch()}.
 * Настоящие сервис записи аудита, обработчик outbox, транзакции, ограничения
 * базы данных и механизм идемпотентной доставки остаются включёнными.</p>
 *
 * <p>Ошибка загрузки Spring ApplicationContext должна диагностироваться
 * по первому исключению в отчёте Surefire, а не обходиться отключением тестов.</p>
 */
@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
class AuditOutboxIntegrationTest extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private AuditOutboxProcessor auditOutboxProcessor;

    /** Исключает автоматическую доставку во время проверок ручного запуска. */
    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void businessTransactionRollbackLeavesNoAuditIntentOrEvent() {
        UUID organizationId = UUID.randomUUID();
        String originalName = "Rollback Audit " + organizationId;
        String changedName = "Rollback Changed " + organizationId;
        insertOrganization(organizationId, originalName, true);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    UPDATE public.organizations
                       SET name = ?
                     WHERE id = ?
                    """, changedName, organizationId);

            // Запись намерения аудита должна разделять транзакцию с бизнес-изменением.
            auditEventService.recordSystemRequiredInCurrentTransaction(
                    organizationId,
                    AuditEventType.ORGANIZATION_NAME_CHANGED,
                    Map.of("newName", changedName));

            throw new IllegalStateException("force rollback");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("force rollback");

        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
        assertThat(queryString("""
                SELECT name
                  FROM public.organizations
                 WHERE id = ?
                """, organizationId)).isEqualTo(originalName);
    }

    @Test
    void businessCommitCreatesDurableIntentAndWorkerCreatesEvent() {
        UUID organizationId = UUID.randomUUID();
        String committedName = "Committed Audit " + organizationId;
        insertOrganization(organizationId, "Commit Audit " + organizationId, true);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    UPDATE public.organizations
                       SET name = ?
                     WHERE id = ?
                    """, committedName, organizationId);

            auditEventService.recordSystemRequiredInCurrentTransaction(
                    organizationId,
                    AuditEventType.ORGANIZATION_NAME_CHANGED,
                    Map.of("newName", committedName));
        });

        assertThat(queryString("""
                SELECT name FROM public.organizations WHERE id = ?
                """, organizationId)).isEqualTo(committedName);
        assertThat(countAuditOutbox()).isEqualTo(1);
        assertThat(countAuditEvents()).isZero();

        UUID outboxId = auditOutboxIdForOrganization(organizationId);

        AuditOutboxProcessor.BatchResult result = auditOutboxProcessor.processBatch();
        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.deadLettered()).isZero();
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isEqualTo(1);

        // Доказательство доставки должно сохранить идентичность исходного намерения.
        Map<String, Object> event = jdbcTemplate.queryForMap("""
                SELECT id, organization_id, event_type, details
                  FROM public.audit_events
                 WHERE id = ?
                """, outboxId);
        assertThat(event.get("id")).isEqualTo(outboxId);
        assertThat(event.get("organization_id")).isEqualTo(organizationId);
        assertThat(event.get("event_type"))
                .isEqualTo(AuditEventType.ORGANIZATION_NAME_CHANGED.name());
        // Строковое поле newName не входит в allowlist legacy Map-полей.
        // Проверяем, что сервис доставил безопасную редактированную версию,
        // а не раскрыл произвольный текст через неизменяемый журнал.
        assertThat(String.valueOf(event.get("details")))
                .contains("[OMITTED_UNAPPROVED_TEXT]")
                .doesNotContain(committedName);
    }

    @Test
    void secondWorkerRunDoesNotCreateDuplicateEvent() {
        UUID organizationId = UUID.randomUUID();
        insertOrganization(organizationId, "Idempotent Audit " + organizationId, true);

        // Для события без бизнес-транзакции используется явная автономная запись.
        auditEventService.recordSystemStandaloneRequired(
                organizationId,
                AuditEventType.USER_CREATED,
                Map.of("test", true));

        UUID eventId = auditOutboxIdForOrganization(organizationId);

        // Моделируем доставку с потерянным подтверждением: событие уже существует,
        // но исходная запись в outbox всё ещё ожидает обработки.
        insertDeliveredAuditEvent(eventId, organizationId,
                Instant.parse("2026-07-30T08:00:00Z"));

        String originalDetails = queryString("""
                SELECT details::text FROM public.audit_events WHERE id = ?
                """, eventId);

        AuditOutboxProcessor.BatchResult first = auditOutboxProcessor.processBatch();
        AuditOutboxProcessor.BatchResult second = auditOutboxProcessor.processBatch();

        assertThat(first.processed()).isEqualTo(1);
        assertThat(first.failed()).isZero();
        assertThat(first.deadLettered()).isZero();
        assertThat(second.processed()).isZero();
        assertThat(second.failed()).isZero();
        assertThat(second.deadLettered()).isZero();
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isEqualTo(1);

        // Повторная доставка не должна перезаписать уже зафиксированное доказательство.
        assertThat(queryString("""
                SELECT details::text FROM public.audit_events WHERE id = ?
                """, eventId)).isEqualTo(originalDetails);
    }

    /**
     * Проверяет, что ограничения V24 и V68 одновременно существуют и валидированы.
     *
     * <p>PostgreSQL может указать в ошибке первое сработавшее ограничение V24,
     * хотя усиленное ограничение V68 тоже установлено. Поэтому структура обоих
     * внешних ключей проверяется отдельно, без ослабления защиты.</p>
     */
    @Test
    void bothEventTypeForeignKeysExistAreValidatedAndProtectReferenceData() {
        assertBothEventTypeForeignKeysExistAreValidatedAndProtectReferenceData();
    }

    /** Сверяет оба исторически независимых ограничения с каталогом PostgreSQL. */
    private void assertBothEventTypeForeignKeysExistAreValidatedAndProtectReferenceData() {
        List<Map<String, Object>> constraints = jdbcTemplate.queryForList("""
                SELECT constraint_record.conname AS constraint_name,
                       constraint_record.convalidated AS validated,
                       constraint_record.conrelid =
                           'public.audit_outbox'::regclass AS correct_source,
                       constraint_record.confrelid =
                           'public.audit_event_types'::regclass AS correct_target,
                       constraint_record.confupdtype::text AS on_update,
                       constraint_record.confdeltype::text AS on_delete,
                       pg_get_constraintdef(constraint_record.oid) AS definition
                  FROM pg_constraint AS constraint_record
                 WHERE constraint_record.conrelid = 'public.audit_outbox'::regclass
                   AND constraint_record.contype = 'f'
                   AND constraint_record.conname IN (
                       'fk_audit_outbox_event_type',
                       'fk_audit_outbox_event_type_v68'
                   )
                 ORDER BY constraint_record.conname
                """);

        assertThat(constraints)
                .as("Внешние ключи миграций V24 и V68 должны существовать одновременно")
                .hasSize(2)
                .extracting(row -> (String) row.get("constraint_name"))
                .containsExactlyInAnyOrder(
                        "fk_audit_outbox_event_type",
                        "fk_audit_outbox_event_type_v68");

        for (Map<String, Object> constraint : constraints) {
            String constraintName = (String) constraint.get("constraint_name");
            assertThat(constraint.get("validated"))
                    .as("Ограничение %s должно быть валидировано", constraintName)
                    .isEqualTo(Boolean.TRUE);
            assertThat(constraint.get("correct_source"))
                    .as("Ограничение %s должно принадлежать audit_outbox", constraintName)
                    .isEqualTo(Boolean.TRUE);
            assertThat(constraint.get("correct_target"))
                    .as("Ограничение %s должно ссылаться на audit_event_types", constraintName)
                    .isEqualTo(Boolean.TRUE);
            assertThat((String) constraint.get("definition"))
                    .as("Ограничение %s должно защищать именно event_type", constraintName)
                    .contains("FOREIGN KEY (event_type)")
                    .contains("audit_event_types(name)");

            // V68 усиливает политику ссылочной целостности явным RESTRICT.
            if ("fk_audit_outbox_event_type_v68".equals(constraintName)) {
                assertThat(constraint.get("on_update"))
                        .as("V68 должен запрещать изменение используемого типа события")
                        .isEqualTo("r");
                assertThat(constraint.get("on_delete"))
                        .as("V68 должен запрещать удаление используемого типа события")
                        .isEqualTo("r");
            }
        }
    }

    @Test
    void unknownEventTypeIsRejectedAtomicallyByDatabasePolicy() {
        UUID organizationId = UUID.randomUUID();
        insertOrganization(organizationId, "Unknown type " + organizationId, true);

        // Проверяем также более поздний усиленный ключ V68, даже если PostgreSQL
        // в сообщении о нарушении укажет созданный ранее ключ V24.
        assertBothEventTypeForeignKeysExistAreValidatedAndProtectReferenceData();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO public.audit_outbox (
                    id, organization_id, event_type, details, created_at
                ) VALUES (?, ?, 'UNKNOWN_EVENT_TYPE', '{}'::jsonb, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), organizationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertPostgresConstraint(
                        failure, "23503",
                        "fk_audit_outbox_event_type",
                        "fk_audit_outbox_event_type_v68"));

        // Проверяем именно политику типа события, а не побочный отказ вставки.
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    @Test
    void databaseDetailsSizeConstraintRejectsOversizedRawInsert() {
        UUID organizationId = UUID.randomUUID();
        insertOrganization(organizationId, "Oversized audit " + organizationId, true);
        String oversized = "{\"payload\":\"" + "x".repeat(70_000) + "\"}";

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO public.audit_events (
                    id, organization_id, event_type, details, created_at
                ) VALUES (?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), organizationId,
                AuditEventType.USER_CREATED.name(), oversized))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertPostgresConstraint(
                        failure, "23514", "chk_audit_events_details_size"));

        assertThat(countAuditEvents()).isZero();
    }

    /**
     * Проверяет SQLSTATE и строго ограниченный набор допустимых ограничений PostgreSQL.
     *
     * <p>Не используем assertThat непосредственно для PSQLException:
     * AssertJ содержит перегрузки, неоднозначные для этого типа.
     * Проверка исключения отделена от проверки его строковых атрибутов.</p>
     *
     * <p>Наличие дополнительного ограничения V68 проверяется отдельно,
     * поскольку PostgreSQL может сообщить о более раннем ключе V24. Отсутствие
     * серверной диагностики или ожидаемого ограничения —
     * самостоятельная ошибка теста. Такой отказ нельзя считать доказательством
     * срабатывания проверяемой защиты базы данных.</p>
     */
    private static void assertPostgresConstraint(Throwable failure,
                                                 String expectedState,
                                                 String... expectedConstraints) {
        PSQLException postgres = findPostgresException(failure);
        if (postgres == null) {
            throw new AssertionError(
                    "В цепочке причин отсутствует PSQLException: "
                            + failure.getClass().getName(), failure);
        }

        // После явной проверки postgres гарантированно не равен null.
        assertThat(postgres.getSQLState())
                .as("SQLSTATE должен соответствовать проверяемому ограничению")
                .isEqualTo(expectedState);

        ServerErrorMessage serverError = postgres.getServerErrorMessage();
        if (serverError == null) {
            throw new AssertionError(
                    "PostgreSQL не предоставил ServerErrorMessage для SQLSTATE "
                            + postgres.getSQLState(), postgres);
        }

        // Не допускаем совпадения с другим ограничением или пустой диагностикой.
        String actualConstraint = serverError.getConstraint();
        assertThat(actualConstraint)
                .as("Нарушено именно ожидаемое ограничение PostgreSQL")
                .isNotBlank()
                .isIn((Object[]) expectedConstraints);
    }

    /** Находит исходную ошибку драйвера в цепочке исключений Spring JDBC. */
    private static PSQLException findPostgresException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PSQLException postgres) {
                return postgres;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Создаёт ранее доставленное неизменяемое событие с тем же идентификатором.
     * Сценарий намеренно не изменяет миграции, триггеры или JPA-репозитории.
     */
    private void insertDeliveredAuditEvent(UUID eventId,
                                           UUID organizationId,
                                           Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO public.audit_events (
                    id, actor_user_id, actor_organization_id,
                    actor_email, actor_display_name,
                    organization_id, event_type, details, created_at
                ) VALUES (?, NULL, NULL, NULL, 'SYSTEM', ?, ?, CAST(? AS jsonb), ?)
                """, eventId, organizationId,
                AuditEventType.USER_CREATED.name(),
                "{\"alreadyDelivered\":true}",
                new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE,
                        createdAt.atOffset(ZoneOffset.UTC)));
    }
}


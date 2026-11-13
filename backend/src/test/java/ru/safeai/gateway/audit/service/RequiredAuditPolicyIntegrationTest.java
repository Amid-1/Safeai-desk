package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Проверяет, что обязательный аудит является частью бизнес-транзакции.
 *
 * <p>Используются настоящие PostgreSQL, Spring-транзакции, сервис формирования
 * события и политика санитизации. Подменяется только граница записи в outbox:
 * это позволяет воспроизвести отказ без отключения транзакционного менеджера
 * и остальных проверок приложения.</p>
 *
 * <p>Важно: успешный тест с mock writer доказывает вызов обязательного аудита,
 * но не физическую доставку события. Доставку проверяют отдельные тесты outbox.</p>
 */
@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
@Import(RequiredAuditPolicyIntegrationTest.PolicyTestConfiguration.class)
class RequiredAuditPolicyIntegrationTest extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private RequiredAuditBusinessOperation businessOperation;

    /** Подменяется только место записи обязательного намерения аудита. */
    @MockitoBean
    private AuditOutboxWriter outboxWriter;

    /** Не допускаем фоновой доставки во время детерминированных проверок. */
    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void requiredAuditFailureRollsBackSecuritySensitiveBusinessOperation() {
        UUID organizationId = UUID.randomUUID();
        String originalName = "Required Before " + organizationId;
        String newName = "Required After " + organizationId;
        insertOrganization(organizationId, originalName, true);

        // Имитируем отказ именно в момент обязательной записи аудита.
        // В момент вызова бизнес-транзакция уже должна быть активна.
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("Обязательный аудит должен вызываться внутри бизнес-транзакции")
                    .isTrue();
            throw new TransactionSystemException("simulated required audit failure");
        }).when(outboxWriter).writeRequiredInCurrentTransaction(any(AuditCommand.class));

        assertThatThrownBy(() -> businessOperation.renameOrganization(organizationId, newName))
                .isInstanceOf(TransactionSystemException.class)
                .hasMessageContaining("simulated required audit failure");

        // Верифицируем сам факт попытки аудита: простой откат без вызова writer
        // не должен превращаться в ложноуспешный тест.
        AuditCommand command = captureRequiredCommand();
        assertRequiredCommand(command, organizationId, newName);
        assertThat(organizationName(organizationId)).isEqualTo(originalName);
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    @Test
    void successfulRequiredAuditInvocationAllowsBusinessCommit() {
        UUID organizationId = UUID.randomUUID();
        String oldName = "Required Success Before " + organizationId;
        String newName = "Required Success After " + organizationId;
        insertOrganization(organizationId, oldName, true);

        // Writer остаётся mock: отсутствие исключения позволяет проверить,
        // что транзакция бизнес-операции действительно фиксируется.
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("Обязательная запись должна выполняться в активной транзакции")
                    .isTrue();
            return null;
        }).when(outboxWriter).writeRequiredInCurrentTransaction(any(AuditCommand.class));

        assertThatCode(() -> businessOperation.renameOrganization(organizationId, newName))
                .doesNotThrowAnyException();

        assertRequiredCommand(captureRequiredCommand(), organizationId, newName);
        assertThat(organizationName(organizationId)).isEqualTo(newName);

        // Настоящий writer в этом тесте заменён mock, поэтому outbox остаётся пуст.
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    @Test
    void missingOrganizationCannotProduceFalseRequiredAuditEvent() {
        UUID missingOrganizationId = UUID.randomUUID();

        assertThatThrownBy(() -> businessOperation.renameOrganization(
                missingOrganizationId, "Missing organization"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ожидалось изменение ровно одной организации");

        // Если изменено ноль строк, намерение аудита создаваться не должно.
        verifyNoInteractions(outboxWriter);
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    @Test
    void secondRequiredAuditFailureAlsoRollsBackIndependentBusinessTransaction() {
        UUID firstOrganizationId = UUID.randomUUID();
        UUID secondOrganizationId = UUID.randomUUID();
        String firstOldName = "First Required Before " + firstOrganizationId;
        String secondOldName = "Second Required Before " + secondOrganizationId;
        String firstNewName = "First Required After " + firstOrganizationId;
        String secondNewName = "Second Required After " + secondOrganizationId;
        insertOrganization(firstOrganizationId, firstOldName, true);
        insertOrganization(secondOrganizationId, secondOldName, true);

        // Одна ошибка обязательного аудита не должна сделать последующие
        // бизнес-транзакции небезопасными или оставить незавершённые изменения.
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new TransactionSystemException("required audit unavailable");
        }).when(outboxWriter).writeRequiredInCurrentTransaction(any(AuditCommand.class));

        assertThatThrownBy(() -> businessOperation.renameOrganization(firstOrganizationId, firstNewName))
                .isInstanceOf(TransactionSystemException.class)
                .hasMessageContaining("required audit unavailable");
        assertThatThrownBy(() -> businessOperation.renameOrganization(secondOrganizationId, secondNewName))
                .isInstanceOf(TransactionSystemException.class)
                .hasMessageContaining("required audit unavailable");

        ArgumentCaptor<AuditCommand> captor = ArgumentCaptor.forClass(AuditCommand.class);
        verify(outboxWriter, times(2)).writeRequiredInCurrentTransaction(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        assertRequiredCommand(captor.getAllValues().get(0), firstOrganizationId, firstNewName);
        assertRequiredCommand(captor.getAllValues().get(1), secondOrganizationId, secondNewName);
        assertThat(captor.getAllValues().get(0).eventId())
                .isNotEqualTo(captor.getAllValues().get(1).eventId());
        assertThat(organizationName(firstOrganizationId)).isEqualTo(firstOldName);
        assertThat(organizationName(secondOrganizationId)).isEqualTo(secondOldName);
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    /** Возвращает команду, которая была передана обязательному writer ровно один раз. */
    private AuditCommand captureRequiredCommand() {
        ArgumentCaptor<AuditCommand> captor = ArgumentCaptor.forClass(AuditCommand.class);
        verify(outboxWriter, times(1)).writeRequiredInCurrentTransaction(captor.capture());
        return Objects.requireNonNull(captor.getValue(), "Writer получил null вместо команды аудита");
    }

    /** Проверяет семантику события и отсутствие утечки произвольного текста. */
    private static void assertRequiredCommand(AuditCommand command,
                                              UUID organizationId,
                                              String newName) {
        assertThat(command).isNotNull();
        assertThat(command.eventId()).isNotNull();
        assertThat(command.targetOrganizationId()).isEqualTo(organizationId);
        assertThat(command.eventType()).isEqualTo(AuditEventType.ORGANIZATION_NAME_CHANGED);
        assertThat(command.occurredAt()).isNotNull();

        AuditActor actor = Objects.requireNonNull(command.actor(),
                "Обязательный аудит должен содержать системного автора");
        assertThat(actor.userId()).isNull();
        assertThat(actor.organizationId()).isNull();
        assertThat(actor.email()).isNull();
        assertThat(actor.displayName()).isEqualTo("SYSTEM");

        // newName — произвольное текстовое поле legacy Map; в V85 оно
        // заменяется фиксированным маркером, а не сохраняется открытым текстом.
        assertThat(command.details())
                .containsEntry("newName", "[OMITTED_UNAPPROVED_TEXT]");
        assertThat(command.details().toString()).doesNotContain(newName);
    }

    private String organizationName(UUID organizationId) {
        String result = jdbcTemplate.queryForObject("""
                SELECT name
                FROM public.organizations
                WHERE id = ?
                """, String.class, organizationId);
        return Objects.requireNonNull(result, "PostgreSQL вернул null вместо имени организации");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PolicyTestConfiguration {
        @Bean
        RequiredAuditBusinessOperation requiredAuditBusinessOperation(
                JdbcTemplate jdbcTemplate,
                AuditEventService auditEventService) {
            return new RequiredAuditBusinessOperation(jdbcTemplate, auditEventService);
        }
    }

    /**
     * Тестовая бизнес-операция выполняется через Spring-прокси.
     * Отказ обязательного аудита должен откатывать изменение той же транзакции.
     */
    public static class RequiredAuditBusinessOperation {
        private final JdbcTemplate jdbcTemplate;
        private final AuditEventService auditEventService;

        public RequiredAuditBusinessOperation(JdbcTemplate jdbcTemplate,
                                              AuditEventService auditEventService) {
            this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate не должен быть null");
            this.auditEventService = Objects.requireNonNull(auditEventService,
                    "auditEventService не должен быть null");
        }

        @Transactional
        public void renameOrganization(UUID organizationId, String newName) {
            Objects.requireNonNull(organizationId, "organizationId не должен быть null");
            Objects.requireNonNull(newName, "newName не должен быть null");
            if (newName.isBlank()) {
                throw new IllegalArgumentException("Имя организации не должно быть пустым");
            }
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Не запущена бизнес-транзакция Spring");
            }

            int updated = jdbcTemplate.update("""
                    UPDATE public.organizations
                    SET name = ?
                    WHERE id = ?
                    """, newName, organizationId);
            if (updated != 1) {
                throw new IllegalStateException(
                        "Ожидалось изменение ровно одной организации, фактически: " + updated);
            }

            // Событие является обязательным: нельзя перехватывать исключение
            // и продолжать коммит бизнес-изменения после ошибки writer.
            auditEventService.recordSystemRequiredInCurrentTransaction(
                    organizationId,
                    AuditEventType.ORGANIZATION_NAME_CHANGED,
                    Map.of("newName", newName));
        }
    }
}

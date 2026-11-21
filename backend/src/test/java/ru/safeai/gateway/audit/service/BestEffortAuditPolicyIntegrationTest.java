package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import org.mockito.ArgumentCaptor;

/**
 * Проверяет независимость необязательного аудита от бизнес-транзакции.
 *
 * <p>PostgreSQL, транзакционный менеджер и сервис best-effort используются реальные.
 * Подменён исключительно writer, чтобы воспроизводить отказ именно на границе
 * самостоятельной записи аудита, не подменяя бизнес-операцию или её транзакцию.</p>
 *
 * <p>Для обязательного аудита такая политика недопустима: его отказ должен
 * отменять соответствующее изменение. Этот класс не меняет политику
 * обязательных событий и не отключает ограничения базы данных.</p>
 */
@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
@Import(BestEffortAuditPolicyIntegrationTest.PolicyTestConfiguration.class)
class BestEffortAuditPolicyIntegrationTest extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private BestEffortBusinessOperation businessOperation;

    /** Отключается только фоновый scheduler, иначе возникнет гонка с ручными проверками. */
    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    /** Подменяется только внешняя граница записи необязательного аудита. */
    @MockitoBean
    private AuditOutboxWriter outboxWriter;

    @Test
    void auditCommitFailureDoesNotRollbackBestEffortBusinessOperation() {
        UUID organizationId = UUID.randomUUID();
        String oldName = "Before Best Effort " + organizationId;
        String newName = "After Best Effort " + organizationId;
        insertOrganization(organizationId, oldName, true);

        // Имитируем исключение, которое могло возникнуть при фиксации
        // независимой транзакции writer. Весь остальной путь остаётся реальным.
        doThrow(new TransactionSystemException("simulated audit commit failure"))
                .when(outboxWriter).writeStandaloneRequired(any(AuditCommand.class));

        assertThatCode(() -> businessOperation.renameOrganization(organizationId, newName))
                .doesNotThrowAnyException();

        // Отказ необязательного аудита не должен отменить завершённое изменение.
        assertThat(organizationName(organizationId)).isEqualTo(newName);
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();

        // Без этой проверки тест мог бы пройти даже при пропуске вызова аудита.
        ArgumentCaptor<AuditCommand> captor = ArgumentCaptor.forClass(AuditCommand.class);
        verify(outboxWriter, times(1)).writeStandaloneRequired(captor.capture());
        assertCommandForOrganization(captor.getValue(), organizationId);
    }

    @Test
    void successfulBestEffortWriteKeepsBusinessCommitAndRequiredMetadata() {
        UUID organizationId = UUID.randomUUID();
        String newName = "Best Effort Success " + organizationId;
        insertOrganization(organizationId, "Before Success " + organizationId, true);

        // Writer подтверждает вызов, но остаётся тестовым mock:
        // это проверка бизнес-политики, не тест доставки настоящего outbox.
        assertThatCode(() -> businessOperation.renameOrganization(organizationId, newName))
                .doesNotThrowAnyException();

        assertThat(organizationName(organizationId)).isEqualTo(newName);
        ArgumentCaptor<AuditCommand> captor = ArgumentCaptor.forClass(AuditCommand.class);
        verify(outboxWriter, times(1)).writeStandaloneRequired(captor.capture());
        assertCommandForOrganization(captor.getValue(), organizationId);
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    @Test
    void missingBusinessTargetFailsBeforeBestEffortAuditIsCalled() {
        UUID missingOrganizationId = UUID.randomUUID();

        // Изменение, затронувшее ноль строк, не считается успешным.
        // Иначе аудит мог бы создать ложное сообщение о смене организации.
        assertThatThrownBy(() -> businessOperation.renameOrganization(
                missingOrganizationId, "Not changed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ожидалось изменение одной организации");

        verifyNoInteractions(outboxWriter);
        assertThat(countAuditOutbox()).isZero();
        assertThat(countAuditEvents()).isZero();
    }

    private String organizationName(UUID organizationId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT name
                FROM public.organizations
                WHERE id = ?
                """, String.class, organizationId),
                "PostgreSQL вернул null вместо имени организации");
    }

    private static void assertCommandForOrganization(AuditCommand command, UUID organizationId) {
        // Фиксируем точное событие и цель: тест не должен проходить
        // при случайной записи аудита другого типа или другой организации.
        assertThat(command).isNotNull();
        assertThat(command.eventId()).isNotNull();
        assertThat(command.targetOrganizationId()).isEqualTo(organizationId);
        assertThat(command.eventType()).isEqualTo(AuditEventType.RATE_LIMIT_EXCEEDED);
        assertThat(command.details())
                .containsEntry("type", "AI_MESSAGE_USER")
                .containsEntry("limit", 100);
        assertThat(command.occurredAt()).isNotNull();
        assertThat(command.actor()).isNotNull();
        assertThat(command.actor().email()).isEqualTo("standalone@test.com");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PolicyTestConfiguration {
        @Bean
        BestEffortBusinessOperation bestEffortBusinessOperation(
                JdbcTemplate jdbcTemplate,
                BestEffortStandaloneAuditService auditService) {
            return new BestEffortBusinessOperation(jdbcTemplate, auditService);
        }
    }

    /**
     * Только тестовая бизнес-операция с настоящей Spring-транзакцией.
     * Класс публичный, чтобы транзакционный прокси мог вызывать его метод.
     */
    public static class BestEffortBusinessOperation {
        private final JdbcTemplate jdbcTemplate;
        private final BestEffortStandaloneAuditService auditService;

        BestEffortBusinessOperation(JdbcTemplate jdbcTemplate,
                                    BestEffortStandaloneAuditService auditService) {
            this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
            this.auditService = Objects.requireNonNull(auditService, "auditService");
        }

        @Transactional
        public void renameOrganization(UUID organizationId, String newName) {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(newName, "newName");

            // Защита от ложноположительного теста, в котором @Transactional
            // не сработал бы из-за обхода Spring-прокси.
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Не запущена бизнес-транзакция Spring");
            }

            int changed = jdbcTemplate.update("""
                    UPDATE public.organizations
                    SET name = ?
                    WHERE id = ?
                    """, newName, organizationId);
            if (changed != 1) {
                throw new IllegalStateException(
                        "Ожидалось изменение одной организации, изменено: " + changed);
            }

            AuditActor actor = new AuditActor(
                    UUID.randomUUID(), UUID.randomUUID(),
                    "standalone@test.com", "Standalone Actor");

            // RATE_LIMIT_EXCEEDED здесь используется как существующий
            // пример события best-effort; для обязательных событий
            // в production используется отдельный required-аудит.
            auditService.tryRecord(actor, organizationId,
                    AuditEventType.RATE_LIMIT_EXCEEDED,
                    Map.of("type", "AI_MESSAGE_USER", "limit", 100));
        }
    }
}

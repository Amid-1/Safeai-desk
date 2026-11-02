package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные проверки неизменяемого снимка автора аудиторского события.
 *
 * <p>V26 сохраняет организацию автора, V68 запрещает изменение доказательств,
 * V70 отделяет удаляемую ссылку на пользователя от неизменяемых сведений.
 * Проверки выполняются на реальном PostgreSQL из Testcontainers.</p>
 */
@SpringBootTest(classes = ru.safeai.gateway.SafeaiBackendApplication.class)
@ActiveProfiles("test")
class AuditActorSnapshotIntegrationTest extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private AuditOutboxProcessor auditOutboxProcessor;

    // Фоновый планировщик отключаем только для детерминированного тестирования:
    // обработчик auditOutboxProcessor вызывается явно, аудит не подменяется моками.
    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void adminOfOwnOrganizationGetsEmailAndNameSnapshot() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String email = "admin-" + adminId + "@test.com";

        insertOrganization(organizationId, "Admin Snapshot " + organizationId, true);
        insertTestUser(adminId, organizationId, email, "Own Organization Admin", "ADMIN");
        recordSnapshot(principal(adminId, organizationId, "ROLE_ADMIN"),
                organizationId, AuditEventType.USER_UPDATED,
                Map.of("targetUserId", UUID.randomUUID()));

        assertSingleDelivery(organizationId, adminId);
        Map<String, Object> row = eventRow(organizationId, adminId);
        assertThat(row.get("actor_user_id")).isEqualTo(adminId);
        assertThat(row.get("actor_organization_id")).isEqualTo(organizationId);
        assertThat(row.get("organization_id")).isEqualTo(organizationId);
        assertThat(row.get("actor_email")).isEqualTo(email);
        assertThat(row.get("actor_display_name")).isEqualTo("Own Organization Admin");
        assertThat(row.get("user_id")).isEqualTo(adminId);
    }

    @Test
    void superAdminManagingForeignOrganizationKeepsOwnActorOrganization() {
        UUID platformOrganizationId = UUID.randomUUID();
        UUID targetOrganizationId = UUID.randomUUID();
        UUID superAdminId = UUID.randomUUID();
        String email = "super-" + superAdminId + "@test.com";

        insertOrganization(platformOrganizationId, "Platform " + platformOrganizationId, true);
        insertOrganization(targetOrganizationId, "Foreign Target " + targetOrganizationId, true);
        insertTestUser(superAdminId, platformOrganizationId, email,
                "Platform Super Admin", "SUPER_ADMIN");

        recordSnapshot(principal(superAdminId, platformOrganizationId, "ROLE_SUPER_ADMIN"),
                targetOrganizationId, AuditEventType.ORGANIZATION_ENABLED_CHANGED,
                Map.of("enabled", false));

        assertSingleDelivery(targetOrganizationId, superAdminId);
        Map<String, Object> row = eventRow(targetOrganizationId, superAdminId);
        assertThat(row.get("actor_user_id")).isEqualTo(superAdminId);
        assertThat(row.get("actor_organization_id")).isEqualTo(platformOrganizationId);
        assertThat(row.get("organization_id")).isEqualTo(targetOrganizationId);
        assertThat(row.get("actor_email")).isEqualTo(email);
        assertThat(row.get("actor_display_name")).isEqualTo("Platform Super Admin");
    }

    @Test
    void changingCurrentUserEmailDoesNotRewriteOldAuditSnapshot() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String oldEmail = "old-" + adminId + "@test.com";
        String newEmail = "new-" + adminId + "@test.com";

        insertOrganization(organizationId, "Email Snapshot " + organizationId, true);
        insertTestUser(adminId, organizationId, oldEmail, "Email Admin", "ADMIN");
        recordSnapshot(principal(adminId, organizationId, "ROLE_ADMIN"),
                organizationId, AuditEventType.USER_UPDATED,
                Map.of("change", "before-email-change"));

        // Меняем профиль до доставки. Событие обязано сохранить снимок времени записи.
        assertThat(jdbcTemplate.update(
                "update public.users set email = ? where id = ?", newEmail, adminId))
                .isEqualTo(1);

        assertSingleDelivery(organizationId, adminId);
        assertThat(eventRow(organizationId, adminId).get("actor_email")).isEqualTo(oldEmail);
        assertThat(jdbcTemplate.queryForObject(
                "select email from public.users where id = ?", String.class, adminId))
                .isEqualTo(newEmail);
    }

    @Test
    void deletingUserNullsCurrentLinkButPreservesAllActorSnapshots() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String email = "delete-" + adminId + "@test.com";

        insertOrganization(organizationId, "Delete Snapshot " + organizationId, true);
        insertTestUser(adminId, organizationId, email, "Deleted Admin", "ADMIN");
        recordSnapshot(principal(adminId, organizationId, "ROLE_ADMIN"),
                organizationId, AuditEventType.USER_PERMANENTLY_DELETED,
                Map.of("targetUserId", UUID.randomUUID()));

        assertSingleDelivery(organizationId, adminId);
        assertThat(eventRow(organizationId, adminId).get("user_id")).isEqualTo(adminId);
        deleteTestUser(adminId);

        assertPreservedSnapshot(organizationId, adminId, email, "Deleted Admin");
    }

    @Test
    void deletingActorBeforeWorkerRunStillPreservesOutboxSnapshot() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String email = "before-worker-" + adminId + "@test.com";

        insertOrganization(organizationId, "Delete Before Worker " + organizationId, true);
        insertTestUser(adminId, organizationId, email,
                "Deleted Before Worker", "ADMIN");
        recordSnapshot(principal(adminId, organizationId, "ROLE_ADMIN"),
                organizationId, AuditEventType.USER_UPDATED, Map.of("test", true));

        // Перед доставкой снимок находится в outbox, а не считывается из users заново.
        assertThat(countAuditOutbox()).isEqualTo(1);
        assertThat(countAuditEvents()).isZero();
        deleteTestUser(adminId);

        assertSingleDelivery(organizationId, adminId);
        assertPreservedSnapshot(organizationId, adminId, email, "Deleted Before Worker");
    }

    @Test
    void directAuditEventUpdateCannotRewriteActorIdentityOrSnapshot() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String email = "immutable-" + adminId + "@test.com";

        insertOrganization(organizationId, "Immutable Snapshot " + organizationId, true);
        insertTestUser(adminId, organizationId, email, "Immutable Admin", "ADMIN");
        recordSnapshot(principal(adminId, organizationId, "ROLE_ADMIN"),
                organizationId, AuditEventType.USER_UPDATED,
                Map.of("targetUserId", UUID.randomUUID()));
        assertSingleDelivery(organizationId, adminId);

        // V68/V70: UPDATE доказательства должен быть запрещён в PostgreSQL,
        // а не только на уровне Java-сервиса.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update public.audit_events set actor_user_id = null "
                        + "where actor_user_id = ? and organization_id = ?",
                adminId, organizationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update public.audit_events set actor_display_name = ? "
                        + "where actor_user_id = ? and organization_id = ?",
                "Tampered", adminId, organizationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");

        Map<String, Object> row = eventRow(organizationId, adminId);
        assertThat(row.get("user_id")).isEqualTo(adminId);
        assertThat(row.get("actor_user_id")).isEqualTo(adminId);
        assertThat(row.get("actor_display_name")).isEqualTo("Immutable Admin");
        assertThat(row.get("actor_email")).isEqualTo(email);
    }

    @Test
    void auditEventHasNoMutableUserIdColumnAndLinkHasBothCascadeConstraints() {
        // Проверяем схему V70: ссылка удаляется каскадно, доказательство — нет.
        assertThat(queryInt("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'audit_events'
                  and column_name = 'user_id'
                """)).isZero();

        assertThat(queryInt("""
                select count(*) from pg_constraint
                where conrelid = 'public.audit_event_user_links'::regclass
                  and contype = 'f' and confdeltype = 'c'
                """)).isEqualTo(2);

        // Усиление: обе внешние связи ведут на ожидаемые таблицы.
        assertThat(queryInt("""
                select count(*) from pg_constraint
                where conrelid = 'public.audit_event_user_links'::regclass
                  and contype = 'f' and confdeltype = 'c'
                  and confrelid in ('public.audit_events'::regclass,
                                    'public.users'::regclass)
                """)).isEqualTo(2);
    }

    /** Записывает настоящий audit outbox в отдельной завершённой транзакции. */
    private void recordSnapshot(SafeAiUserPrincipal principal, UUID targetOrganizationId,
                                AuditEventType eventType, Map<String, Object> details) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                auditEventService.recordPrincipalSnapshot(
                        principal, targetOrganizationId, eventType, () -> details));
    }

    /** Проверяет именно нужное событие и отсутствие ошибок доставки. */
    private void assertSingleDelivery(UUID targetOrganizationId, UUID actorId) {
        AuditOutboxProcessor.BatchResult result = auditOutboxProcessor.processBatch();
        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.deadLettered()).isZero();
        assertThat(countAuditOutbox()).isZero();
        assertThat(queryInt("""
                select count(*) from public.audit_events
                where organization_id = ? and actor_user_id = ?
                """, targetOrganizationId, actorId)).isEqualTo(1);
    }

    /** Читает событие только заданного автора и заданной целевой организации. */
    private Map<String, Object> eventRow(UUID targetOrganizationId, UUID actorId) {
        return jdbcTemplate.queryForMap("""
                select event.actor_user_id, event.actor_organization_id,
                       event.actor_email, event.actor_display_name,
                       event.organization_id, link.user_id
                from public.audit_events as event
                left join public.audit_event_user_links as link
                  on link.audit_event_id = event.id
                where event.organization_id = ? and event.actor_user_id = ?
                """, targetOrganizationId, actorId);
    }

    /** После удаления пользователя снимок и само событие должны остаться. */
    private void assertPreservedSnapshot(UUID organizationId, UUID actorId,
                                         String email, String displayName) {
        Map<String, Object> row = eventRow(organizationId, actorId);
        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("actor_user_id")).isEqualTo(actorId);
        assertThat(row.get("actor_organization_id")).isEqualTo(organizationId);
        assertThat(row.get("actor_email")).isEqualTo(email);
        assertThat(row.get("actor_display_name")).isEqualTo(displayName);
    }

    private SafeAiUserPrincipal principal(UUID userId, UUID organizationId, String authority) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                userId, organizationId, 0L, 0L,
                List.of(new SimpleGrantedAuthority(authority)));
    }
}

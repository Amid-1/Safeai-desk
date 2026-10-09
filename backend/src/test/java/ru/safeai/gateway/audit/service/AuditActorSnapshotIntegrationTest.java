package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.testsupport.AbstractAuditPostgresIntegrationTest;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ru.safeai.gateway.SafeaiBackendApplication.class
)
@ActiveProfiles("test")
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class AuditActorSnapshotIntegrationTest
        extends AbstractAuditPostgresIntegrationTest {

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AuditOutboxProcessor auditOutboxProcessor;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void adminOfOwnOrganizationGetsEmailAndNameSnapshot() {
        UUID organizationId =
                UUID.randomUUID();

        UUID adminId =
                UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Admin Snapshot "
                        + organizationId,
                true
        );

        insertTestUser(
                adminId,
                organizationId,
                "admin-"
                        + adminId
                        + "@test.com",
                "Own Organization Admin",
                "ADMIN"
        );

        SafeAiUserPrincipal principal =
                principal(
                        adminId,
                        organizationId,
                        "ROLE_ADMIN"
                );

        transactionTemplate.executeWithoutResult(status ->
                auditEventService.recordPrincipalSnapshot(
                principal,
                organizationId,
                AuditEventType.USER_UPDATED,
                () -> Map.of(
                        "targetUserId",
                        UUID.randomUUID()
                )
        ));

        assertThat(
                auditOutboxProcessor
                        .processBatch()
                        .processed()
        ).isEqualTo(1);

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        """
                        select actor_user_id,
                               actor_organization_id,
                               actor_email,
                               actor_display_name,
                               organization_id
                        from public.audit_events
                        """
                );

        assertThat(
                row.get("actor_user_id")
        ).isEqualTo(adminId);

        assertThat(
                row.get(
                        "actor_organization_id"
                )
        ).isEqualTo(
                organizationId
        );

        assertThat(
                row.get("actor_email")
        ).isEqualTo(
                "admin-"
                        + adminId
                        + "@test.com"
        );

        assertThat(
                row.get(
                        "actor_display_name"
                )
        ).isEqualTo(
                "Own Organization Admin"
        );

        assertThat(
                row.get("organization_id")
        ).isEqualTo(
                organizationId
        );
    }

    @Test
    void superAdminManagingForeignOrganizationKeepsOwnActorOrganization() {
        UUID platformOrganizationId =
                UUID.randomUUID();

        UUID targetOrganizationId =
                UUID.randomUUID();

        UUID superAdminId =
                UUID.randomUUID();

        insertOrganization(
                platformOrganizationId,
                "Platform "
                        + platformOrganizationId,
                true
        );

        insertOrganization(
                targetOrganizationId,
                "Foreign Target "
                        + targetOrganizationId,
                true
        );

        insertTestUser(
                superAdminId,
                platformOrganizationId,
                "super-"
                        + superAdminId
                        + "@test.com",
                "Platform Super Admin",
                "SUPER_ADMIN"
        );

        SafeAiUserPrincipal principal =
                principal(
                        superAdminId,
                        platformOrganizationId,
                        "ROLE_SUPER_ADMIN"
                );

        transactionTemplate.executeWithoutResult(status ->
                auditEventService.recordPrincipalSnapshot(
                principal,
                targetOrganizationId,
                AuditEventType
                        .ORGANIZATION_ENABLED_CHANGED,
                () -> Map.of(
                        "enabled",
                        false
                )
        ));

        auditOutboxProcessor
                .processBatch();

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        """
                        select actor_user_id,
                               actor_organization_id,
                               actor_email,
                               actor_display_name,
                               organization_id
                        from public.audit_events
                        """
                );

        assertThat(
                row.get("actor_user_id")
        ).isEqualTo(
                superAdminId
        );

        assertThat(
                row.get(
                        "actor_organization_id"
                )
        ).isEqualTo(
                platformOrganizationId
        );

        assertThat(
                row.get("organization_id")
        ).isEqualTo(
                targetOrganizationId
        );

        assertThat(
                row.get("actor_email")
        ).isEqualTo(
                "super-"
                        + superAdminId
                        + "@test.com"
        );

        assertThat(
                row.get(
                        "actor_display_name"
                )
        ).isEqualTo(
                "Platform Super Admin"
        );
    }

    @Test
    void changingCurrentUserEmailDoesNotRewriteOldAuditSnapshot() {
        UUID organizationId =
                UUID.randomUUID();

        UUID adminId =
                UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Email Snapshot "
                        + organizationId,
                true
        );

        String oldEmail =
                "old-"
                        + adminId
                        + "@test.com";

        String newEmail =
                "new-"
                        + adminId
                        + "@test.com";

        insertTestUser(
                adminId,
                organizationId,
                oldEmail,
                "Email Admin",
                "ADMIN"
        );

        transactionTemplate.executeWithoutResult(status ->
                auditEventService.recordPrincipalSnapshot(
                principal(
                        adminId,
                        organizationId,
                        "ROLE_ADMIN"
                ),
                organizationId,
                AuditEventType.USER_UPDATED,
                () -> Map.of(
                        "change",
                        "before-email-change"
                )
        ));

        jdbcTemplate.update(
                """
                update public.users
                set email = ?
                where id = ?
                """,
                newEmail,
                adminId
        );

        auditOutboxProcessor
                .processBatch();

        assertThat(
                queryString(
                        """
                        select actor_email
                        from public.audit_events
                        """
                )
        ).isEqualTo(
                oldEmail
        );

        assertThat(
                queryString(
                        """
                        select email
                        from public.users
                        where id = ?
                        """,
                        adminId
                )
        ).isEqualTo(
                newEmail
        );
    }

    @Test
    void deletingUserNullsCurrentLinkButPreservesAllActorSnapshots() {
        UUID organizationId =
                UUID.randomUUID();

        UUID adminId =
                UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Delete Snapshot "
                        + organizationId,
                true
        );

        String email =
                "delete-"
                        + adminId
                        + "@test.com";

        insertTestUser(
                adminId,
                organizationId,
                email,
                "Deleted Admin",
                "ADMIN"
        );

        transactionTemplate.executeWithoutResult(status ->
                auditEventService.recordPrincipalSnapshot(
                principal(
                        adminId,
                        organizationId,
                        "ROLE_ADMIN"
                ),
                organizationId,
                AuditEventType
                        .USER_PERMANENTLY_DELETED,
                () -> Map.of(
                        "targetUserId",
                        UUID.randomUUID()
                )
        ));

        auditOutboxProcessor
                .processBatch();

        UUID linkedBeforeDelete =
                jdbcTemplate.queryForObject(
                        """
                        select link.user_id
                        from public.audit_events as event
                        left join public.audit_event_user_links as link
                          on link.audit_event_id = event.id
                        """,
                        UUID.class
                );

        assertThat(
                linkedBeforeDelete
        ).isEqualTo(
                adminId
        );

        deleteTestUser(
                adminId
        );

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        """
                        select link.user_id as user_id,
                               event.actor_user_id,
                               event.actor_organization_id,
                               event.actor_email,
                               event.actor_display_name
                        from public.audit_events as event
                        left join public.audit_event_user_links as link
                          on link.audit_event_id = event.id
                        """
                );

        assertThat(
                row.get("user_id")
        ).isNull();

        assertThat(
                row.get("actor_user_id")
        ).isEqualTo(
                adminId
        );

        assertThat(
                row.get(
                        "actor_organization_id"
                )
        ).isEqualTo(
                organizationId
        );

        assertThat(
                row.get("actor_email")
        ).isEqualTo(
                email
        );

        assertThat(
                row.get(
                        "actor_display_name"
                )
        ).isEqualTo(
                "Deleted Admin"
        );
    }

    @Test
    void deletingActorBeforeWorkerRunStillPreservesOutboxSnapshot() {
        UUID organizationId =
                UUID.randomUUID();

        UUID adminId =
                UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Delete Before Worker "
                        + organizationId,
                true
        );

        String email =
                "before-worker-"
                        + adminId
                        + "@test.com";

        insertTestUser(
                adminId,
                organizationId,
                email,
                "Deleted Before Worker",
                "ADMIN"
        );

        transactionTemplate.executeWithoutResult(status ->
                auditEventService.recordPrincipalSnapshot(
                principal(
                        adminId,
                        organizationId,
                        "ROLE_ADMIN"
                ),
                organizationId,
                AuditEventType.USER_UPDATED,
                () -> Map.of(
                        "test",
                        true
                )
        ));

        assertThat(
                countAuditOutbox()
        ).isEqualTo(1);

        deleteTestUser(
                adminId
        );

        auditOutboxProcessor
                .processBatch();

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        """
                        select link.user_id as user_id,
                               event.actor_user_id,
                               event.actor_organization_id,
                               event.actor_email,
                               event.actor_display_name
                        from public.audit_events as event
                        left join public.audit_event_user_links as link
                          on link.audit_event_id = event.id
                        """
                );

        assertThat(
                row.get("user_id")
        ).isNull();

        assertThat(
                row.get("actor_user_id")
        ).isEqualTo(
                adminId
        );

        assertThat(
                row.get(
                        "actor_organization_id"
                )
        ).isEqualTo(
                organizationId
        );

        assertThat(
                row.get("actor_email")
        ).isEqualTo(
                email
        );

        assertThat(
                row.get(
                        "actor_display_name"
                )
        ).isEqualTo(
                "Deleted Before Worker"
        );
    }

    @Test
    void directAuditEventUpdateCannotRewriteActorIdentityOrSnapshot() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Immutable Snapshot " + organizationId,
                true
        );

        insertTestUser(
                adminId,
                organizationId,
                "immutable-" + adminId + "@test.com",
                "Immutable Admin",
                "ADMIN"
        );

        transactionTemplate.executeWithoutResult(status ->
                auditEventService.recordPrincipalSnapshot(
                        principal(adminId, organizationId, "ROLE_ADMIN"),
                        organizationId,
                        AuditEventType.USER_UPDATED,
                        () -> Map.of("targetUserId", UUID.randomUUID())
                )
        );

        assertThat(auditOutboxProcessor.processBatch().processed())
                .isEqualTo(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "update public.audit_events set actor_user_id = null where actor_user_id = ?",
                        adminId
                )
        ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "update public.audit_events set actor_display_name = ? where actor_user_id = ?",
                        "Tampered",
                        adminId
                )
        ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                select link.user_id as user_id,
                       event.actor_user_id,
                       event.actor_display_name
                from public.audit_events as event
                left join public.audit_event_user_links as link
                  on link.audit_event_id = event.id
                where event.actor_user_id = ?
                """,
                adminId
        );
        assertThat(row.get("user_id")).isEqualTo(adminId);
        assertThat(row.get("actor_user_id")).isEqualTo(adminId);
        assertThat(row.get("actor_display_name")).isEqualTo("Immutable Admin");
    }

    @Test
    void auditEventHasNoMutableUserIdColumnAndLinkHasBothCascadeConstraints() {
        Integer legacyColumnCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'audit_events'
                  and column_name = 'user_id'
                """,
                Integer.class
        );
        assertThat(legacyColumnCount).isZero();

        Integer cascadeForeignKeys = jdbcTemplate.queryForObject(
                """
                select count(*)
                from pg_constraint
                where conrelid = 'public.audit_event_user_links'::regclass
                  and contype = 'f'
                  and confdeltype = 'c'
                """,
                Integer.class
        );
        assertThat(cascadeForeignKeys).isEqualTo(2);
    }

    private SafeAiUserPrincipal principal(
            UUID userId,
            UUID organizationId,
            String authority
    ) {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        userId,
                        organizationId,
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        authority
                                )
                        )
                );
    }
}


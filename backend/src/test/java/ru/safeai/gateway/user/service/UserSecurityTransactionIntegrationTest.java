package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class UserSecurityTransactionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private UserService userService;

    @MockitoBean
    private AuditEventService auditEventService;

    @MockitoBean
    private UserStatusCacheService userStatusCacheService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    @Test
    void successfulSecurityMutationRevokesRefreshSessionInSameCommit() {
        prepareUserWithActiveRefreshToken();

        userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(false, 0L),
                adminPrincipal()
        );

        assertThat(userEnabled(USER_ID)).isFalse();
        assertThat(tokenVersion(USER_ID)).isEqualTo(1L);

        String reason = jdbcTemplate.queryForObject("""
                select token.revocation_reason
                from public.refresh_tokens as token
                where token.user_id = ?
                """, String.class, USER_ID);

        assertThat(reason).isEqualTo(RefreshTokenRevocationReason.USER_DISABLED.name());

        verify(auditEventService).recordPrincipalSnapshot(
                argThat((SafeAiUserPrincipal principal) ->
                        principal != null
                                && ADMIN_ID.equals(principal.getId())
                                && ORGANIZATION_ID.equals(principal.getOrganizationId())),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_ENABLED_CHANGED),
                argThat((AuditDetails details) ->
                        details != null && isDisabledAuditDetails(details.toMap()))
        );
        verify(userStatusCacheService).evict(USER_ID);
    }

    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    @Test
    void auditFailureRollsBackMutationAndRefreshRevocation() {
        prepareUserWithActiveRefreshToken();

        doThrow(new IllegalStateException("audit outbox unavailable"))
                .when(auditEventService).recordPrincipalSnapshot(
                        any(SafeAiUserPrincipal.class),
                        eq(ORGANIZATION_ID),
                        eq(AuditEventType.USER_ENABLED_CHANGED),
                        any(AuditDetails.class)
                );

        assertThatThrownBy(() -> userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(false, 0L),
                adminPrincipal()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit outbox unavailable");

        assertThat(userEnabled(USER_ID)).isTrue();
        assertThat(tokenVersion(USER_ID)).isZero();

        Integer activeTokens = jdbcTemplate.queryForObject("""
                select count(*)
                from public.refresh_tokens as token
                where token.user_id = ? and token.revoked_at is null
                """, Integer.class, USER_ID);

        assertThat(activeTokens).isEqualTo(1);
        verify(auditEventService).recordPrincipalSnapshot(
                argThat((SafeAiUserPrincipal principal) ->
                        principal != null
                                && ADMIN_ID.equals(principal.getId())
                                && ORGANIZATION_ID.equals(principal.getOrganizationId())),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_ENABLED_CHANGED),
                argThat((AuditDetails details) ->
                        details != null && isDisabledAuditDetails(details.toMap()))
        );
        verify(userStatusCacheService, never()).evict(USER_ID);
    }

    private static boolean isDisabledAuditDetails(Map<String, Object> details) {
        return details.size() == 7
                && USER_ID.toString().equals(details.get("targetUserId"))
                && "transaction-user@test.com".equals(details.get("targetUserEmail"))
                && ORGANIZATION_ID.toString().equals(details.get("targetOrganizationId"))
                && Boolean.TRUE.equals(details.get("oldEnabled"))
                && Boolean.FALSE.equals(details.get("newEnabled"))
                && Boolean.TRUE.equals(details.get("changed"))
                && Boolean.TRUE.equals(details.get("sessionsRevoked"));
    }

    private void prepareUserWithActiveRefreshToken() {
        insertOrganization(ORGANIZATION_ID, "Security Transaction Organization", true);
        insertUser(
                USER_ID,
                ORGANIZATION_ID,
                "transaction-user@test.com",
                true,
                "USER",
                Instant.parse("2026-07-20T10:00:00Z")
        );
        insertActiveRefreshToken(USER_ID, 0L);
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }
}

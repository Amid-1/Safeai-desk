package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class UserSecurityTransactionIntegrationTest
        extends AbstractPostgresIntegrationTest {

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

    @SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@Test
void successfulSecurityMutationRevokesRefreshSessionInSameCommit() {
    prepareUserWithActiveRefreshToken();

    userService.updateEnabled(
            USER_ID,
            new UpdateUserEnabledRequest(false),
            adminPrincipal()
    );

    assertThat(userEnabled(USER_ID)).isFalse();
    assertThat(tokenVersion(USER_ID)).isEqualTo(1L);

    String reason = jdbcTemplate.queryForObject("""
            select token.revocation_reason
            from public.refresh_tokens as token
            where token.user_id = ?
            """, String.class, USER_ID);

    assertThat(reason).isEqualTo(
            RefreshTokenRevocationReason.USER_DISABLED.name()
    );

    verify(userStatusCacheService).evict(USER_ID);
}

    @SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@Test
void auditFailureRollsBackMutationAndRefreshRevocation() {
    prepareUserWithActiveRefreshToken();

    doThrow(
            new IllegalStateException(
                    "audit outbox unavailable"
            )
    )
            .when(auditEventService)
            .record(
                    any(SafeAiUserPrincipal.class),
                    any(UUID.class),
                    any(AuditEventType.class),
                    anyMap()
            );

    SafeAiUserPrincipal currentUser =
            adminPrincipal();

    assertThatThrownBy(() ->
            userService.updateEnabled(
                    USER_ID,
                    new UpdateUserEnabledRequest(false),
                    currentUser
            )
    )
            .isInstanceOf(
                    IllegalStateException.class
            )
            .hasMessageContaining(
                    "audit outbox unavailable"
            );

    assertThat(userEnabled(USER_ID))
            .isTrue();

    assertThat(tokenVersion(USER_ID))
            .isZero();

    Integer activeTokens =
            jdbcTemplate.queryForObject("""
                    select count(*)
                    from public.refresh_tokens as token
                    where token.user_id = ?
                      and token.revoked_at is null
                    """,
                    Integer.class,
                    USER_ID
            );

    assertThat(activeTokens)
            .isEqualTo(1);

    verify(userStatusCacheService, never())
            .evict(USER_ID);
}

    private void prepareUserWithActiveRefreshToken() {
        insertOrganization(
                ORGANIZATION_ID,
                "Security Transaction Organization",
                true
        );
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
                "admin@test.com",
                0L,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }


}

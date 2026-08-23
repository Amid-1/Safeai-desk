package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.BestEffortStandaloneAuditService;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthEventServiceTest {

    private static final UUID USER_ID =
            UUID.randomUUID();

    private static final UUID ORGANIZATION_ID =
            UUID.randomUUID();

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.randomUUID();

    private static final String EMAIL =
            "admin@test.com";

    @Mock
    private BestEffortStandaloneAuditService auditService;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private HttpServletRequest request;

    private AuthEventService service;

    @BeforeEach
    void setUp() {
        service = new AuthEventService(
                auditService,
                new PlatformProperties(
                        PLATFORM_ORGANIZATION_ID
                ),
                clientIpResolver
        );

        when(clientIpResolver.resolve(request))
                .thenReturn("127.0.0.1");

        when(request.getHeader("User-Agent"))
                .thenReturn("JUnit");

        when(request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        )).thenReturn("request-1");
    }

    @Test
    void loginSuccessUsesCanonicalCurrentUserSnapshot() {
        CurrentUserResponse user =
                new CurrentUserResponse(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        "Admin Name",
                        true,
                        Set.of("ADMIN")
                );

        service.loginSuccess(
                user,
                request
        );

        ArgumentCaptor<AuditActor> actorCaptor =
                ArgumentCaptor.forClass(
                        AuditActor.class
                );

        verify(auditService).tryRecord(
                actorCaptor.capture(),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .USER_LOGIN_SUCCESS
                ),
                anyMap()
        );

        AuditActor actor =
                actorCaptor.getValue();

        assertThat(actor.userId())
                .isEqualTo(USER_ID);

        assertThat(actor.organizationId())
                .isEqualTo(ORGANIZATION_ID);

        assertThat(actor.email())
                .isEqualTo(EMAIL);

        assertThat(actor.displayName())
                .isEqualTo(
                        "Admin Name"
                );
    }

    @Test
    void loginFailureUsesConfiguredPlatformOrganization() {
        service.loginFailed(
                "user@test.com",
                request
        );

        verify(auditService).tryRecordSystem(
                eq(PLATFORM_ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .USER_LOGIN_FAILED
                ),
                anyMap()
        );
    }

    @Test
    void refreshReuseUsesTypedDetails() {
        UUID familyId =
                UUID.randomUUID();

        RefreshTokenReuseDetectedException exception =
                new RefreshTokenReuseDetectedException(
                        "reuse",
                        USER_ID,
                        ORGANIZATION_ID,
                        familyId
                );

        service.refreshReuseDetected(
                exception,
                request
        );

        ArgumentCaptor<AuditDetails> detailsCaptor =
                ArgumentCaptor.forClass(
                        AuditDetails.class
                );

        verify(auditService).tryRecord(
                eq(
                        new AuditActor(
                                USER_ID,
                                ORGANIZATION_ID,
                                null,
                                null
                        )
                ),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .SECURITY_REFRESH_REUSE_DETECTED
                ),
                detailsCaptor.capture()
        );

        assertThat(
                detailsCaptor.getValue()
                        .toMap()
        )
                .containsEntry(
                        "tokenFamilyId",
                        familyId
                )
                .containsEntry(
                        "ip",
                        "127.0.0.1"
                )
                .containsEntry(
                        "requestId",
                        "request-1"
                );
    }

    @Test
    void logoutUsesImmutableLogoutSubject() {
        LogoutAuditSubject subject =
                new LogoutAuditSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        "logout@test.com"
                );

        service.logout(
                subject,
                request
        );

        verify(auditService).tryRecord(
                eq(
                        new AuditActor(
                                USER_ID,
                                ORGANIZATION_ID,
                                "logout@test.com",
                                null
                        )
                ),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType.USER_LOGOUT
                ),
                anyMap()
        );
    }
}

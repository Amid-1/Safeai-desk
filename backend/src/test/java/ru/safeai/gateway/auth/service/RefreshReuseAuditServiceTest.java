package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.SecurityRefreshReuseAuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.AuditCommand;
import ru.safeai.gateway.audit.service.AuditCommandFactory;
import ru.safeai.gateway.audit.service.AuditOutboxWriter;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshReuseAuditServiceTest {

    @Mock
    private AuditCommandFactory auditCommandFactory;

    @Mock
    private AuditOutboxWriter auditOutboxWriter;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private AuditCommand auditCommand;

    @Test
    void writesRequiredReuseEvidenceIntoCurrentTransaction() {
        UUID userId =
                UUID.randomUUID();

        UUID organizationId =
                UUID.randomUUID();

        UUID familyId =
                UUID.randomUUID();

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.addHeader(
                "User-Agent",
                "JUnit"
        );

        request.setAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                "req-123"
        );

        when(
                clientIpResolver.resolve(
                        request
                )
        ).thenReturn(
                "127.0.0.1"
        );

        when(
                auditCommandFactory.createTyped(
                        any(AuditActor.class),
                        eq(organizationId),
                        eq(
                                AuditEventType
                                        .SECURITY_REFRESH_REUSE_DETECTED
                        ),
                        any(SecurityRefreshReuseAuditDetails.class)
                )
        ).thenReturn(
                auditCommand
        );

        RefreshReuseAuditService service =
                new RefreshReuseAuditService(
                        auditCommandFactory,
                        auditOutboxWriter,
                        clientIpResolver
                );

        service.recordRequired(
                userId,
                organizationId,
                familyId,
                request
        );

        ArgumentCaptor<AuditActor> actorCaptor =
                ArgumentCaptor.forClass(
                        AuditActor.class
                );

        ArgumentCaptor<SecurityRefreshReuseAuditDetails>
                detailsCaptor =
                ArgumentCaptor.forClass(
                        SecurityRefreshReuseAuditDetails.class
                );

        verify(
                auditCommandFactory
        ).createTyped(
                actorCaptor.capture(),
                eq(organizationId),
                eq(
                        AuditEventType
                                .SECURITY_REFRESH_REUSE_DETECTED
                ),
                detailsCaptor.capture()
        );

        AuditActor actor =
                actorCaptor.getValue();

        assertThat(
                actor.userId()
        ).isEqualTo(
                userId
        );

        assertThat(
                actor.organizationId()
        ).isEqualTo(
                organizationId
        );

        SecurityRefreshReuseAuditDetails details =
                detailsCaptor.getValue();

        assertThat(
                details.tokenFamilyId()
        ).isEqualTo(
                familyId
        );

        assertThat(
                details.ip()
        ).isEqualTo(
                "127.0.0.1"
        );

        assertThat(
                details.userAgent()
        ).isEqualTo(
                "JUnit"
        );

        assertThat(
                details.requestId()
        ).isEqualTo(
                "req-123"
        );

        verify(
                auditOutboxWriter
        ).writeRequiredInCurrentTransaction(
                auditCommand
        );
    }
}

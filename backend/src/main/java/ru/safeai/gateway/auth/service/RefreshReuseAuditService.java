package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.SecurityRefreshReuseAuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.AuditCommand;
import ru.safeai.gateway.audit.service.AuditCommandFactory;
import ru.safeai.gateway.audit.service.AuditOutboxWriter;
import ru.safeai.gateway.common.security.ClientIpResolver;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable security evidence for refresh-token reuse.
 *
 * <p>This service is intentionally MANDATORY: it may only run inside the same
 * database transaction that terminates the refresh-token family. Family
 * termination and the audit outbox intent therefore commit atomically.</p>
 */
@Service
@RequiredArgsConstructor
public class RefreshReuseAuditService {

    private final AuditCommandFactory
            auditCommandFactory;

    private final AuditOutboxWriter
            auditOutboxWriter;

    private final ClientIpResolver
            clientIpResolver;

    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public void recordRequired(
            UUID userId,
            UUID organizationId,
            UUID tokenFamilyId,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                tokenFamilyId,
                "tokenFamilyId не должен быть null"
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        AuditActor actor =
                new AuditActor(
                        userId,
                        organizationId,
                        null,
                        null
                );

        SecurityRefreshReuseAuditDetails details =
                new SecurityRefreshReuseAuditDetails(
                        tokenFamilyId,
                        AuthAuditRequestSupport
                                .ipOrUnknown(
                                        clientIpResolver,
                                        request
                                ),
                        AuthAuditRequestSupport
                                .userAgentOrUnknown(
                                        request
                                ),
                        AuthAuditRequestSupport
                                .requestIdOrUnknown(
                                        request
                                )
                );

        AuditCommand command =
                auditCommandFactory.createTyped(
                        actor,
                        organizationId,
                        AuditEventType
                                .SECURITY_REFRESH_REUSE_DETECTED,
                        details
                );

        auditOutboxWriter
                .writeRequiredInCurrentTransaction(
                        command
                );
    }
}

package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.BestEffortStandaloneAuditService;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.ClientIpResolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Best-effort auth observability for non-critical auth events.
 *
 * <p>Refresh-token reuse is intentionally absent from this service. Reuse is
 * required security evidence and is written transactionally by
 * {@link RefreshReuseAuditService} together with token-family termination.</p>
 */
@Service
@RequiredArgsConstructor
public class AuthEventService {

    /*
     * Must match actor_email / actor_display_name snapshot limits.
     */
    private static final int MAX_IDENTITY_LENGTH =
            255;

    private final BestEffortStandaloneAuditService
            auditService;

    private final PlatformProperties
            platformProperties;

    private final ClientIpResolver
            clientIpResolver;

    public void loginSuccess(
            CurrentUserResponse user,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                user,
                "user не должен быть null"
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                user.id(),
                "user.id не должен быть null"
        );

        Objects.requireNonNull(
                user.organizationId(),
                "user.organizationId не должен быть null"
        );

        AuditActor actor =
                new AuditActor(
                        user.id(),
                        user.organizationId(),
                        AuthAuditRequestSupport
                                .truncateNullable(
                                        user.email(),
                                        MAX_IDENTITY_LENGTH
                                ),
                        AuthAuditRequestSupport
                                .truncateNullable(
                                        user.fullName(),
                                        MAX_IDENTITY_LENGTH
                                )
                );

        auditService.tryRecord(
                actor,
                user.organizationId(),
                AuditEventType.USER_LOGIN_SUCCESS,
                requestDetails(
                        request,
                        Map.of(
                                "email",
                                AuthAuditRequestSupport
                                        .truncateOrUnknown(
                                                user.email(),
                                                MAX_IDENTITY_LENGTH
                                        ),
                                "organizationId",
                                user.organizationId()
                        )
                )
        );
    }

    public void loginFailed(
            String email,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        auditService.tryRecordSystem(
                platformProperties.organizationId(),
                AuditEventType.USER_LOGIN_FAILED,
                requestDetails(
                        request,
                        Map.of(
                                "email",
                                AuthAuditRequestSupport
                                        .truncateOrUnknown(
                                                email,
                                                MAX_IDENTITY_LENGTH
                                        ),
                                "reason",
                                "BAD_CREDENTIALS_OR_DISABLED"
                        )
                )
        );
    }

    public void logout(
            LogoutAuditSubject subject,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                subject,
                "subject не должен быть null"
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                subject.userId(),
                "subject.userId не должен быть null"
        );

        Objects.requireNonNull(
                subject.organizationId(),
                "subject.organizationId не должен быть null"
        );

        AuditActor actor =
                new AuditActor(
                        subject.userId(),
                        subject.organizationId(),
                        AuthAuditRequestSupport
                                .truncateNullable(
                                        subject.email(),
                                        MAX_IDENTITY_LENGTH
                                ),
                        null
                );

        auditService.tryRecord(
                actor,
                subject.organizationId(),
                AuditEventType.USER_LOGOUT,
                requestDetails(
                        request,
                        Map.of(
                                "email",
                                AuthAuditRequestSupport
                                        .truncateOrUnknown(
                                                subject.email(),
                                                MAX_IDENTITY_LENGTH
                                        ),
                                "organizationId",
                                subject.organizationId()
                        )
                )
        );
    }

    private Map<String, Object> requestDetails(
            HttpServletRequest request,
            Map<String, Object> eventDetails
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                eventDetails,
                "eventDetails не должен быть null"
        );

        Map<String, Object> result =
                new LinkedHashMap<>(
                        eventDetails
                );

        result.put(
                "ip",
                AuthAuditRequestSupport
                        .ipOrUnknown(
                                clientIpResolver,
                                request
                        )
        );

        result.put(
                "userAgent",
                AuthAuditRequestSupport
                        .userAgentOrUnknown(
                                request
                        )
        );

        result.put(
                "requestId",
                AuthAuditRequestSupport
                        .requestIdOrUnknown(
                                request
                        )
        );

        String clientRequestId =
                AuthAuditRequestSupport
                        .clientRequestIdOrNull(
                                request
                        );

        if (clientRequestId != null) {
            result.put(
                    "clientRequestId",
                    clientRequestId
            );
        }

        return Map.copyOf(
                result
        );
    }
}

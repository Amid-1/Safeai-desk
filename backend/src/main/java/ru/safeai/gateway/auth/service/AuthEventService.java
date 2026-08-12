package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.SecurityRefreshReuseAuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.BestEffortStandaloneAuditService;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuthEventService {

    /*
     * Must match the actor_email / actor_display_name snapshot limits.
     */
    private static final int MAX_IDENTITY_LENGTH = 255;

    private static final int MAX_IP_LENGTH = 128;

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private static final int MAX_REQUEST_ID_LENGTH = 128;

    private static final String UNKNOWN_VALUE =
            "unknown";

    /*
     * AuthService invokes these methods after the login/refresh/logout
     * transaction has already completed. Therefore, audit persistence is a
     * standalone best-effort operation and must not turn a committed auth
     * operation into an HTTP failure.
     */
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
                        truncateNullable(
                                user.email(),
                                MAX_IDENTITY_LENGTH
                        ),
                        truncateNullable(
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
                                truncateOrUnknown(
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
                                truncateOrUnknown(
                                        email,
                                        MAX_IDENTITY_LENGTH
                                ),
                                "reason",
                                "BAD_CREDENTIALS_OR_DISABLED"
                        )
                )
        );
    }

    public void refreshReuseDetected(
            RefreshTokenReuseDetectedException exception,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                exception,
                "exception не должен быть null"
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                exception.getUserId(),
                "exception.userId не должен быть null"
        );

        Objects.requireNonNull(
                exception.getOrganizationId(),
                "exception.organizationId не должен быть null"
        );

        Objects.requireNonNull(
                exception.getTokenFamilyId(),
                "exception.tokenFamilyId не должен быть null"
        );

        AuditActor actor =
                new AuditActor(
                        exception.getUserId(),
                        exception.getOrganizationId(),
                        null,
                        null
                );

        SecurityRefreshReuseAuditDetails details =
                new SecurityRefreshReuseAuditDetails(
                        exception.getTokenFamilyId(),
                        truncateOrUnknown(
                                clientIpResolver.resolve(
                                        request
                                ),
                                MAX_IP_LENGTH
                        ),
                        userAgentOrUnknown(
                                request
                        ),
                        requestId(
                                request
                        )
                );

        auditService.tryRecord(
                actor,
                exception.getOrganizationId(),
                AuditEventType
                        .SECURITY_REFRESH_REUSE_DETECTED,
                details
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
                        truncateNullable(
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
                                truncateOrUnknown(
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
                truncateOrUnknown(
                        clientIpResolver.resolve(
                                request
                        ),
                        MAX_IP_LENGTH
                )
        );

        result.put(
                "userAgent",
                userAgentOrUnknown(
                        request
                )
        );

        /*
         * requestId всегда является server-generated correlation id.
         *
         * Входящий X-Request-Id является только client metadata
         * и никогда не используется вместо server requestId.
         */
        result.put(
                "requestId",
                requestId(
                        request
                )
        );

        String clientRequestId =
                clientRequestId(
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

    private String requestId(
            HttpServletRequest request
    ) {
        Object attribute =
                request.getAttribute(
                        RequestIdFilter
                                .REQUEST_ID_ATTRIBUTE
                );

        if (attribute instanceof String value
                && !value.isBlank()) {

            return truncateOrUnknown(
                    value,
                    MAX_REQUEST_ID_LENGTH
            );
        }

        /*
         * Не используем client-controlled X-Request-Id
         * в качестве server correlation id.
         */
        return UNKNOWN_VALUE;
    }

    private @Nullable String clientRequestId(
            HttpServletRequest request
    ) {
        Object attribute =
                request.getAttribute(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_ATTRIBUTE
                );

        if (!(attribute instanceof String value)
                || value.isBlank()) {

            return null;
        }

        return truncateNullable(
                value,
                MAX_REQUEST_ID_LENGTH
        );
    }

    private String userAgentOrUnknown(
            HttpServletRequest request
    ) {
        return truncateOrUnknown(
                request.getHeader(
                        "User-Agent"
                ),
                MAX_USER_AGENT_LENGTH
        );
    }

    private String truncateOrUnknown(
            @Nullable String value,
            int maxLength
    ) {
        String normalized =
                truncateNullable(
                        value,
                        maxLength
                );

        return normalized == null
                ? UNKNOWN_VALUE
                : normalized;
    }

    private @Nullable String truncateNullable(
            @Nullable String value,
            int maxLength
    ) {
        if (value == null
                || value.isBlank()) {

            return null;
        }

        if (maxLength <= 0) {
            throw new IllegalArgumentException(
                    "maxLength должен быть положительным"
            );
        }

        String trimmed =
                value.trim();

        if (trimmed.length()
                <= maxLength) {

            return trimmed;
        }

        int end =
                maxLength;

        /*
         * Не обрываем UTF-16 surrogate pair.
         */
        if (Character.isHighSurrogate(
                trimmed.charAt(
                        end - 1
                )
        )) {
            end--;
        }

        return trimmed.substring(
                0,
                end
        );
    }
}
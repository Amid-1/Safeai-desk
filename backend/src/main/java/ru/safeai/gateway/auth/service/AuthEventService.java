package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthEventService {

    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final AuthAuditTransactionService auditTransactionService;
    private final PlatformProperties platformProperties;
    private final ClientIpResolver clientIpResolver;

    public void loginSuccess(
            CurrentUserResponse user,
            HttpServletRequest request
    ) {
        try {
            auditTransactionService.record(
                    user.id(),
                    user.organizationId(),
                    AuditEventType.USER_LOGIN_SUCCESS,
                    Map.of(
                            "email", truncateOrUnknown(
                                    user.email(),
                                    MAX_EMAIL_LENGTH
                            ),
                            "organizationId",
                            user.organizationId().toString(),
                            "ip", clientIpResolver.resolve(request),
                            "userAgent", headerOrUnknown(
                                    request,
                                    "User-Agent",
                                    MAX_USER_AGENT_LENGTH
                            ),
                            "requestId", requestId(request)
                    )
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to commit USER_LOGIN_SUCCESS audit event for userId={}",
                    user.id(),
                    exception
            );
        }
    }

    public void loginFailed(
            String email,
            HttpServletRequest request
    ) {
        try {
            auditTransactionService.recordSystem(
                    platformProperties.organizationId(),
                    AuditEventType.USER_LOGIN_FAILED,
                    Map.of(
                            "email", truncateOrUnknown(
                                    email,
                                    MAX_EMAIL_LENGTH
                            ),
                            "reason", "BAD_CREDENTIALS_OR_DISABLED",
                            "ip", clientIpResolver.resolve(request),
                            "userAgent", headerOrUnknown(
                                    request,
                                    "User-Agent",
                                    MAX_USER_AGENT_LENGTH
                            ),
                            "requestId", requestId(request)
                    )
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to commit USER_LOGIN_FAILED audit event for email={}",
                    email,
                    exception
            );
        }
    }

    public void loginRateLimitExceeded(
            String email,
            HttpServletRequest request,
            RateLimitExceededException exception
    ) {
        try {
            auditTransactionService.recordSystem(
                    platformProperties.organizationId(),
                    AuditEventType.RATE_LIMIT_EXCEEDED,
                    Map.of(
                            "type", "LOGIN",
                            "email", truncateOrUnknown(
                                    email,
                                    MAX_EMAIL_LENGTH
                            ),
                            "ip", clientIpResolver.resolve(request),
                            "userAgent", headerOrUnknown(
                                    request,
                                    "User-Agent",
                                    MAX_USER_AGENT_LENGTH
                            ),
                            "requestId", requestId(request),
                            "retryAfterSeconds",
                            exception.getRetryAfterSeconds()
                    )
            );
        } catch (RuntimeException auditException) {
            log.warn(
                    "Failed to commit LOGIN RATE_LIMIT_EXCEEDED audit event for email={}",
                    email,
                    auditException
            );
        }
    }

    public void refreshReuseDetected(
            RefreshTokenReuseDetectedException exception,
            HttpServletRequest request
    ) {
        try {
            auditTransactionService.record(
                    exception.getUserId(),
                    exception.getOrganizationId(),
                    AuditEventType.SECURITY_REFRESH_REUSE_DETECTED,
                    Map.of(
                            "tokenFamilyId",
                            exception.getTokenFamilyId().toString(),
                            "ip", clientIpResolver.resolve(request),
                            "userAgent", headerOrUnknown(
                                    request,
                                    "User-Agent",
                                    MAX_USER_AGENT_LENGTH
                            ),
                            "requestId", requestId(request)
                    )
            );
        } catch (RuntimeException auditException) {
            log.warn(
                    "Failed to commit SECURITY_REFRESH_REUSE_DETECTED audit event for userId={}",
                    exception.getUserId(),
                    auditException
            );
        }
    }

    public void logout(
            LogoutAuditSubject subject,
            HttpServletRequest request
    ) {
        try {
            auditTransactionService.record(
                    subject.userId(),
                    subject.organizationId(),
                    AuditEventType.USER_LOGOUT,
                    Map.of(
                            "email", truncateOrUnknown(
                                    subject.email(),
                                    MAX_EMAIL_LENGTH
                            ),
                            "organizationId",
                            subject.organizationId().toString(),
                            "ip", clientIpResolver.resolve(request),
                            "userAgent", headerOrUnknown(
                                    request,
                                    "User-Agent",
                                    MAX_USER_AGENT_LENGTH
                            ),
                            "requestId", requestId(request)
                    )
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to commit USER_LOGOUT audit event for userId={}",
                    subject.userId(),
                    exception
            );
        }
    }

    private String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );

        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }

        return headerOrUnknown(
                request,
                RequestIdFilter.REQUEST_ID_HEADER,
                128
        );
    }

    private String headerOrUnknown(
            HttpServletRequest request,
            String name,
            int maxLength
    ) {
        return truncateOrUnknown(request.getHeader(name), maxLength);
    }

    private String truncateOrUnknown(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        String trimmed = value.trim();
        return trimmed.length() <= maxLength
                ? trimmed
                : trimmed.substring(0, maxLength);
    }
}

package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.RequestIdFilter;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthEventService {

    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final AuditEventService auditEventService;
    private final PlatformProperties platformProperties;
    private final ClientIpResolver clientIpResolver;

    public void loginSuccess(SafeAiUserPrincipal principal, HttpServletRequest request) {
        try {
            auditEventService.record(
                    principal.getId(),
                    principal.getOrganizationId(),
                    AuditEventType.USER_LOGIN_SUCCESS,
                    Map.of(
                            "email", truncateOrUnknown(principal.getEmail(), MAX_EMAIL_LENGTH),
                            "organizationId", principal.getOrganizationId().toString(),
                            "ip", clientIpResolver.resolve(request),
                            "userAgent", headerOrUnknown(request, "User-Agent", MAX_USER_AGENT_LENGTH),
                            "requestId", requestId(request)
                    )
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to write USER_LOGIN_SUCCESS audit event for userId={}",
                    principal.getId(),
                    exception
            );
        }
    }

    public void loginFailed(String email, HttpServletRequest request) {
        try {
            auditEventService.recordSystem(
                    platformProperties.effectiveOrganizationId(),
                    AuditEventType.USER_LOGIN_FAILED,
                    Map.of(
                            "email", truncateOrUnknown(email, MAX_EMAIL_LENGTH),
                            "reason", "BAD_CREDENTIALS_OR_DISABLED",
                            "ip", clientIpResolver.resolve(request),
                            "userAgent", headerOrUnknown(request, "User-Agent", MAX_USER_AGENT_LENGTH),
                            "requestId", requestId(request)
                    )
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to write USER_LOGIN_FAILED audit event for email={}",
                    email,
                    exception
            );
        }
    }

    public void refreshReuseDetected(
            RefreshTokenReuseDetectedException exception,
            HttpServletRequest request
    ) {
        try {
            auditEventService.record(
                    exception.getUserId(),
                    exception.getOrganizationId(),
                    AuditEventType.SECURITY_REFRESH_REUSE_DETECTED,
                    Map.of(
                            "tokenFamilyId", exception.getTokenFamilyId().toString(),
                            "ip", clientIpResolver.resolve(request),
                            "userAgent", headerOrUnknown(request, "User-Agent", MAX_USER_AGENT_LENGTH),
                            "requestId", requestId(request)
                    )
            );
        } catch (RuntimeException auditException) {
            log.warn(
                    "Failed to write SECURITY_REFRESH_REUSE_DETECTED audit event for userId={}",
                    exception.getUserId(),
                    auditException
            );
        }
    }

    public void logout(SafeAiUserPrincipal principal, HttpServletRequest request) {
        try {
            auditEventService.record(
                    principal.getId(),
                    principal.getOrganizationId(),
                    AuditEventType.USER_LOGOUT,
                    Map.of(
                            "email", truncateOrUnknown(principal.getEmail(), MAX_EMAIL_LENGTH),
                            "organizationId", principal.getOrganizationId().toString(),
                            "ip", clientIpResolver.resolve(request),
                            "userAgent", headerOrUnknown(request, "User-Agent", MAX_USER_AGENT_LENGTH),
                            "requestId", requestId(request)
                    )
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to write USER_LOGOUT audit event for userId={}",
                    principal.getId(),
                    exception
            );
        }
    }

    private String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);

        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }

        return headerOrUnknown(request, RequestIdFilter.REQUEST_ID_HEADER, 128);
    }

    private String headerOrUnknown(HttpServletRequest request, String name, int maxLength) {
        return truncateOrUnknown(request.getHeader(name), maxLength);
    }

    private String truncateOrUnknown(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        String trimmed = value.trim();

        if (trimmed.length() <= maxLength) {
            return trimmed;
        }

        return trimmed.substring(0, maxLength);
    }
}
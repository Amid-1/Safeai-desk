package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthEventService {

    private final AuditEventService auditEventService;

    public void loginSuccess(SafeAiUserPrincipal principal, HttpServletRequest request) {
        auditEventService.record(
                principal.getId(),
                AuditEventType.USER_LOGIN_SUCCESS,
                Map.of(
                        "email", principal.getEmail(),
                        "organizationId", principal.getOrganizationId().toString(),
                        "ip", extractClientIp(request),
                        "userAgent", getHeaderOrUnknown(request, "User-Agent"),
                        "requestId", getHeaderOrUnknown(request, "X-Request-Id")
                )
        );
    }

    public void loginFailed(String email, HttpServletRequest request) {
        auditEventService.record(
                null,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of(
                        "email", email,
                        "reason", "BAD_CREDENTIALS_OR_DISABLED",
                        "ip", extractClientIp(request),
                        "userAgent", getHeaderOrUnknown(request, "User-Agent"),
                        "requestId", getHeaderOrUnknown(request, "X-Request-Id")
                )
        );
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");

        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }

    private String getHeaderOrUnknown(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
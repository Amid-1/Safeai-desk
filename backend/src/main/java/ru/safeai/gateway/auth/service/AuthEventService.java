package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthEventService {

    private final AuditEventService auditEventService;
    private final ClientIpResolver clientIpResolver;
    private final PlatformProperties platformProperties;

    public void loginSuccess(SafeAiUserPrincipal principal, HttpServletRequest request) {
        auditEventService.record(
                principal.getId(),
                principal.getOrganizationId(),
                AuditEventType.USER_LOGIN_SUCCESS,
                Map.of(
                        "email", principal.getEmail(),
                        "organizationId", principal.getOrganizationId().toString(),
                        "ip", clientIpResolver.resolve(request),
                        "userAgent", getHeaderOrUnknown(request, "User-Agent"),
                        "requestId", getHeaderOrUnknown(request, "X-Request-Id")
                )
        );
    }

    public void loginFailed(String email, HttpServletRequest request) {
        auditEventService.recordSystem(
                platformProperties.effectiveOrganizationId(),
                AuditEventType.USER_LOGIN_FAILED,
                Map.of(
                        "email", email,
                        "reason", "BAD_CREDENTIALS_OR_DISABLED",
                        "ip", clientIpResolver.resolve(request),
                        "userAgent", getHeaderOrUnknown(request, "User-Agent"),
                        "requestId", getHeaderOrUnknown(request, "X-Request-Id")
                )
        );
    }

    private String getHeaderOrUnknown(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
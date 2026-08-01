package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

@ConfigurationProperties(prefix = "safeai.rate-limit.login")
public record LoginRateLimitProperties(
        Boolean enabled,
        Integer emailLimit,
        Integer ipLimit,
        Duration window,
        UUID auditOrganizationId
) {
    private static final int MAX_LIMIT = 10_000;

    private static final Duration MIN_WINDOW =
            Duration.ofSeconds(1);

    private static final Duration MAX_WINDOW =
            Duration.ofHours(24);

    public LoginRateLimitProperties {
        enabled = enabled == null || enabled;

        emailLimit = requireLimit(
                emailLimit,
                "safeai.rate-limit.login.email-limit"
        );

        ipLimit = requireLimit(
                ipLimit,
                "safeai.rate-limit.login.ip-limit"
        );

        if (window == null
                || window.compareTo(MIN_WINDOW) < 0
                || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalStateException(
                    "safeai.rate-limit.login.window должен быть "
                            + "в диапазоне 1s–24h"
            );
        }

        if (enabled && auditOrganizationId == null) {
            throw new IllegalStateException(
                    "safeai.rate-limit.login.audit-organization-id "
                            + "не задан"
            );
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int effectiveEmailLimit() {
        return emailLimit;
    }

    public int effectiveIpLimit() {
        return ipLimit;
    }

    public Duration effectiveWindow() {
        return window;
    }

    public UUID effectiveAuditOrganizationId() {
        if (auditOrganizationId == null) {
            throw new IllegalStateException(
                    "safeai.rate-limit.login.audit-organization-id "
                            + "не задан"
            );
        }

        return auditOrganizationId;
    }

    private static int requireLimit(
            Integer value,
            String propertyName
    ) {
        if (value == null || value < 1 || value > MAX_LIMIT) {
            throw new IllegalStateException(
                    propertyName
                            + " должен быть в диапазоне 1–"
                            + MAX_LIMIT
            );
        }

        return value;
    }
}

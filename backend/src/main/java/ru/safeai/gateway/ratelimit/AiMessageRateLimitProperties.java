package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.rate-limit.ai-messages")
public record AiMessageRateLimitProperties(
        Boolean enabled,
        Integer userLimitPerHour,
        Integer adminLimitPerHour,
        Integer organizationLimitPerHour
) {
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public int effectiveUserLimitPerHour() {
        return userLimitPerHour == null || userLimitPerHour <= 0
                ? 20
                : userLimitPerHour;
    }

    public int effectiveAdminLimitPerHour() {
        return adminLimitPerHour == null || adminLimitPerHour <= 0
                ? 100
                : adminLimitPerHour;
    }

    public int effectiveOrganizationLimitPerHour() {
        return organizationLimitPerHour == null || organizationLimitPerHour <= 0
                ? 1000
                : organizationLimitPerHour;
    }
}
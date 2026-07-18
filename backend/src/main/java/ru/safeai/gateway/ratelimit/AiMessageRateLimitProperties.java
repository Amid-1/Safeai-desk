package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.rate-limit.ai-messages")
public record AiMessageRateLimitProperties(
        Boolean enabled,
        Integer userLimitPerHour,
        Integer adminLimitPerHour,
        Integer organizationLimitPerHour
) {
    private static final int MAX_LIMIT = 1_000_000;

    public AiMessageRateLimitProperties {
        enabled = enabled == null || enabled;

        userLimitPerHour = requireLimit(
                userLimitPerHour,
                "safeai.rate-limit.ai-messages.user-limit-per-hour"
        );

        adminLimitPerHour = requireLimit(
                adminLimitPerHour,
                "safeai.rate-limit.ai-messages.admin-limit-per-hour"
        );

        organizationLimitPerHour = requireLimit(
                organizationLimitPerHour,
                "safeai.rate-limit.ai-messages.organization-limit-per-hour"
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int effectiveUserLimitPerHour() {
        return userLimitPerHour;
    }

    public int effectiveAdminLimitPerHour() {
        return adminLimitPerHour;
    }

    public int effectiveOrganizationLimitPerHour() {
        return organizationLimitPerHour;
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

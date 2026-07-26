package ru.safeai.gateway.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.user-management")
public record UserManagementProperties(
        Duration permanentDeletionRetention
) {
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(7);
    private static final Duration MAX_RETENTION = Duration.ofDays(365);

    public UserManagementProperties {
        if (permanentDeletionRetention == null) {
            permanentDeletionRetention = DEFAULT_RETENTION;
        }

        if (permanentDeletionRetention.isNegative()) {
            throw new IllegalStateException(
                    "safeai.user-management.permanent-deletion-retention "
                            + "не может быть отрицательным"
            );
        }

        if (permanentDeletionRetention.compareTo(MAX_RETENTION) > 0) {
            throw new IllegalStateException(
                    "safeai.user-management.permanent-deletion-retention "
                            + "не должен превышать 365 дней"
            );
        }
    }
}

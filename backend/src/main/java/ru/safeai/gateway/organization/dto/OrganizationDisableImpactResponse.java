package ru.safeai.gateway.organization.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Advisory snapshot shown BEFORE an organization is disabled.
 *
 * <p>Counts are not a lock or authorization guarantee. Database security
 * fencing (organization enabled flag + authVersion, including V72 provider
 * STARTED-attempt gate) is enforced by the actual disable transaction.
 * In-flight remote requests cannot be forcibly cancelled by a preview.</p>
 */
public record OrganizationDisableImpactResponse(
        UUID organizationId,
        long organizationVersion,
        long enabledUsers,
        long administrators,
        long activeRefreshSessions,
        long activeChatOperations,
        long startedPhysicalAttempts,
        long uncertainPhysicalAttempts,
        boolean advisoryOnly
) {
    public OrganizationDisableImpactResponse {
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        requireNonNegative(organizationVersion, "organizationVersion");
        requireNonNegative(enabledUsers, "enabledUsers");
        requireNonNegative(administrators, "administrators");
        requireNonNegative(activeRefreshSessions, "activeRefreshSessions");
        requireNonNegative(activeChatOperations, "activeChatOperations");
        requireNonNegative(startedPhysicalAttempts, "startedPhysicalAttempts");
        requireNonNegative(uncertainPhysicalAttempts, "uncertainPhysicalAttempts");

        if (!advisoryOnly) {
            throw new IllegalArgumentException(
                    "disable-impact является предварительным снимком, а не гарантией"
            );
        }
    }

    /** Source compatibility for older consumers of the six-field response. */
    public OrganizationDisableImpactResponse(
            UUID organizationId,
            long organizationVersion,
            long enabledUsers,
            long administrators,
            long activeRefreshSessions,
            long activeChatOperations
    ) {
        this(organizationId, organizationVersion, enabledUsers, administrators,
                activeRefreshSessions, activeChatOperations, 0L, 0L, true);
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " не может быть отрицательным");
        }
    }
}

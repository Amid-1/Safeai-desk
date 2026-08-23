package ru.safeai.gateway.audit.service;

import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public final class AuditEventQueryPolicy {

    /*
     * Frontend allows 366 local calendar days.
     *
     * Because conversion from a local calendar range to Instant may cross
     * DST boundaries, the server permits up to 367 * 24h as a defensive
     * transport-level ceiling.
     */
    private static final Duration MAX_QUERY_WINDOW =
            Duration.ofDays(367);

    private AuditEventQueryPolicy() {
    }

    public static QueryScope resolve(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter
    ) {
        requirePrincipal(currentUser);
        requireAuditReader(currentUser);

        boolean superAdmin =
                isSuperAdmin(currentUser);

        AuditEventFilter effectiveFilter =
                filter == null
                        ? emptyFilter()
                        : filter;

        validateDateRange(
                effectiveFilter
        );

        UUID ownOrganizationId =
                superAdmin
                        ? null
                        : requireTenantOrganizationId(
                                currentUser
                        );

        validateOrganizationFilter(
                effectiveFilter,
                superAdmin,
                ownOrganizationId
        );

        UUID enforcedOrganizationId =
                superAdmin
                        ? effectiveFilter.organizationId()
                        : ownOrganizationId;

        return new QueryScope(
                effectiveFilter,
                enforcedOrganizationId
        );
    }

    /**
     * Application-layer authorization boundary.
     *
     * <p>HTTP audit endpoints уже защищены через {@code @PreAuthorize},
     * однако policy дополнительно запрещает прямой service-level доступ
     * пользователям без ADMIN/SUPER_ADMIN.</p>
     */
    private static void requireAuditReader(
            SafeAiUserPrincipal currentUser
    ) {
        if (!isAdmin(currentUser)
                && !isSuperAdmin(currentUser)) {

            throw new ForbiddenOperationException(
                    "Аудит доступен только ADMIN или SUPER_ADMIN"
            );
        }
    }

    private static boolean isAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return hasAuthority(
                currentUser,
                SystemRole.ADMIN
        );
    }

    private static boolean isSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return hasAuthority(
                currentUser,
                SystemRole.SUPER_ADMIN
        );
    }

    private static boolean hasAuthority(
            SafeAiUserPrincipal currentUser,
            SystemRole role
    ) {
        return currentUser
                .authorityNames()
                .contains(
                        role.authority()
                );
    }

    private static UUID requireTenantOrganizationId(
            SafeAiUserPrincipal currentUser
    ) {
        return Objects.requireNonNull(
                currentUser.getOrganizationId(),
                "organizationId текущего ADMIN не должен быть null"
        );
    }

    private static void validateOrganizationFilter(
            AuditEventFilter filter,
            boolean superAdmin,
            UUID ownOrganizationId
    ) {
        if (superAdmin
                || filter.organizationId() == null) {
            return;
        }

        if (!filter.organizationId().equals(
                ownOrganizationId
        )) {
            throw new ForbiddenOperationException(
                    "Нельзя фильтровать аудит другой организации"
            );
        }
    }

    private static void validateDateRange(
            AuditEventFilter filter
    ) {
        if (filter.dateFrom() == null
                || filter.dateTo() == null) {
            return;
        }

        if (!filter.dateFrom().isBefore(
                filter.dateTo()
        )) {
            throw new BadRequestException(
                    "dateFrom должен быть раньше dateTo"
            );
        }

        Duration window =
                Duration.between(
                        filter.dateFrom(),
                        filter.dateTo()
                );

        if (window.compareTo(
                MAX_QUERY_WINDOW
        ) > 0) {
            throw new BadRequestException(
                    "Период аудита не должен превышать 367 суток"
            );
        }
    }

    private static void requirePrincipal(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
    }

    private static AuditEventFilter emptyFilter() {
        return new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public record QueryScope(
            AuditEventFilter filter,
            UUID organizationId
    ) {
        public QueryScope {
            Objects.requireNonNull(
                    filter,
                    "filter не должен быть null"
            );
        }
    }
}
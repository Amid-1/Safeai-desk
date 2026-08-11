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
     * Frontend allows 366 local calendar days. Because conversion to Instant
     * crosses DST boundaries, the server permits up to 367 * 24h as a
     * defensive transport-level ceiling.
     */
    private static final Duration MAX_QUERY_WINDOW =
            Duration.ofDays(367);

    private AuditEventQueryPolicy() {
    }

    public static QueryScope resolve(
            SafeAiUserPrincipal currentUser,
            AuditEventFilter filter
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        AuditEventFilter effectiveFilter =
                filter == null
                        ? emptyFilter()
                        : filter;

        validateDateRange(effectiveFilter);

        boolean superAdmin = currentUser
                .authorityNames()
                .contains(
                        SystemRole.SUPER_ADMIN.authority()
                );

        UUID ownOrganizationId = superAdmin
                ? null
                : requireTenantOrganizationId(currentUser);

        validateOrganizationFilter(
                effectiveFilter,
                superAdmin,
                ownOrganizationId
        );

        UUID enforcedOrganizationId = superAdmin
                ? effectiveFilter.organizationId()
                : ownOrganizationId;

        return new QueryScope(
                effectiveFilter,
                enforcedOrganizationId
        );
    }

    private static UUID requireTenantOrganizationId(
            SafeAiUserPrincipal currentUser
    ) {
        return currentUser.getOrganizationId();
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

        Duration window = Duration.between(
                filter.dateFrom(),
                filter.dateTo()
        );

        if (window.compareTo(MAX_QUERY_WINDOW) > 0) {
            throw new BadRequestException(
                    "Период аудита не должен превышать 367 суток"
            );
        }
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
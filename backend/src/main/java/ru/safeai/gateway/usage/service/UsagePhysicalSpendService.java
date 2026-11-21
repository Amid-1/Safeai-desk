package ru.safeai.gateway.usage.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.dto.UsagePhysicalSpendResponse;
import ru.safeai.gateway.usage.dto.UsagePhysicalReconciliationResponse;
import ru.safeai.gateway.usage.dto.UsagePhysicalReportResponse;
import ru.safeai.gateway.usage.repository.UsagePhysicalSpendRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Physical spend is a separate evidence contract, not a replacement for message reports. */
@Service
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UsagePhysicalSpendService {

    private final UsagePhysicalSpendRepository repository;
    private final UsageReportExecutor executor;
    private final UsageProperties properties;
    private final Clock clock;

    public UsagePhysicalSpendService(
            UsagePhysicalSpendRepository repository,
            UsageReportExecutor executor,
            UsageProperties properties,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.executor = Objects.requireNonNull(executor);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public UsagePhysicalSpendResponse findCoverage(
            Instant dateFrom, Instant dateTo, SafeAiUserPrincipal currentUser
    ) {
        QueryScope scope = scope(dateFrom, dateTo, currentUser);
        return executor.execute("physical-attempts", () ->
                repository.findCoverage(scope.from(), scope.to(), scope.organizationId()));
    }

    public UsagePhysicalReconciliationResponse reconcile(
            Instant dateFrom, Instant dateTo, SafeAiUserPrincipal currentUser
    ) {
        QueryScope scope = scope(dateFrom, dateTo, currentUser);
        return executor.execute("physical-reconciliation", () ->
                repository.reconcile(scope.from(), scope.to(), scope.organizationId()));
    }

    /** Обе выборки выполняются под одним допуском и снимком REPEATABLE READ. */
    public UsagePhysicalReportResponse report(
            Instant dateFrom, Instant dateTo, UUID requestedOrganization,
            SafeAiUserPrincipal currentUser
    ) {
        QueryScope base = scope(dateFrom, dateTo, currentUser);
        if (requestedOrganization != null && !UsageAccessPolicy.isSuperAdmin(currentUser)
                && !requestedOrganization.equals(base.organizationId())) {
            throw new ForbiddenOperationException("Нет доступа к физическому отчёту организации");
        }
        UUID organizationId = requestedOrganization == null ? base.organizationId() : requestedOrganization;
        return executor.execute("physical-report", () -> UsagePhysicalReportResponse.from(
                repository.findCoverage(base.from(), base.to(), organizationId),
                repository.reconcile(base.from(), base.to(), organizationId)
        ));
    }

    public java.util.List<ru.safeai.gateway.usage.dto.UsageProviderRow> providers(
            Instant from, Instant to, UUID organizationId, String model, UUID userId,
            SafeAiUserPrincipal currentUser
    ) {
        QueryScope base = scope(from, to, currentUser);
        if (organizationId != null && !UsageAccessPolicy.isSuperAdmin(currentUser)
                && !organizationId.equals(base.organizationId())) {
            throw new ForbiddenOperationException("Нет доступа к организации");
        }
        if (model != null && (model.length() > 100 || model.chars().anyMatch(Character::isISOControl))) {
            throw new BadRequestException("Некорректная модель");
        }
        UUID tenant = organizationId == null ? base.organizationId() : organizationId;
        return executor.execute("physical-providers", () ->
                repository.findProviders(base.from(), base.to(), tenant, model, userId));
    }

    public ru.safeai.gateway.usage.dto.UsageProviderReportResponse providerReport(
            Instant from, Instant to, UUID organizationId, String model, UUID userId,
            SafeAiUserPrincipal currentUser
    ) {
        var rows = providers(from, to, organizationId, model, userId, currentUser);
        UUID tenant = organizationId != null || UsageAccessPolicy.isSuperAdmin(currentUser)
                ? organizationId : currentUser.getOrganizationId();
        return new ru.safeai.gateway.usage.dto.UsageProviderReportResponse(
                from.toString(), to.toString(), tenant, model, userId, "PHYSICAL_ATTEMPT_STARTED_AT", rows);
    }

    private QueryScope scope(
            Instant dateFrom, Instant dateTo, SafeAiUserPrincipal currentUser
    ) {
        UsageAccessPolicy.requireReader(currentUser);
        Instant to = dateTo == null ? clock.instant() : dateTo;
        Instant from = dateFrom == null ? to.minus(properties.defaultRange()) : dateFrom;
        if (!from.isBefore(to)) {
            throw new BadRequestException("dateFrom должен быть раньше dateTo");
        }
        if (Duration.between(from, to).compareTo(properties.maxRange()) > 0) {
            throw new BadRequestException("Период physical usage превышает safeai.usage.max-range");
        }
        UUID organizationId = UsageAccessPolicy.isSuperAdmin(currentUser)
                ? null : Objects.requireNonNull(currentUser.getOrganizationId(), "organizationId");
        return new QueryScope(from, to, organizationId);
    }

    private record QueryScope(Instant from, Instant to, UUID organizationId) {}

}

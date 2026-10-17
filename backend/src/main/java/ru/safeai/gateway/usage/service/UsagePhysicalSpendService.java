package ru.safeai.gateway.usage.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.dto.UsagePhysicalSpendResponse;
import ru.safeai.gateway.usage.dto.UsagePhysicalReconciliationResponse;
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

    private QueryScope scope(
            Instant dateFrom, Instant dateTo, SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(currentUser, "currentUser");
        Instant to = dateTo == null ? clock.instant() : dateTo;
        Instant from = dateFrom == null ? to.minus(properties.defaultRange()) : dateFrom;
        if (!from.isBefore(to)) {
            throw new BadRequestException("dateFrom должен быть раньше dateTo");
        }
        if (Duration.between(from, to).compareTo(properties.maxRange()) > 0) {
            throw new BadRequestException("Период physical usage превышает safeai.usage.max-range");
        }
        UUID organizationId = currentUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals)
                ? null : Objects.requireNonNull(currentUser.getOrganizationId(), "organizationId");
        return new QueryScope(from, to, organizationId);
    }

    private record QueryScope(Instant from, Instant to, UUID organizationId) {}

}

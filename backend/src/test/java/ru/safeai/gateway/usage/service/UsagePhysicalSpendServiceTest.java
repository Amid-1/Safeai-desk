package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.dto.UsagePhysicalReconciliationResponse;
import ru.safeai.gateway.usage.dto.UsagePhysicalSpendResponse;
import ru.safeai.gateway.usage.repository.UsagePhysicalSpendRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsagePhysicalSpendServiceTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Mock UsagePhysicalSpendRepository repository;
    private UsagePhysicalSpendService service;

    @BeforeEach
    void setUp() {
        UsageProperties properties = new UsageProperties(
                Duration.ofDays(30), Duration.ofDays(366), Duration.ofDays(31),
                200, Duration.ofSeconds(10), Duration.ofSeconds(2),
                200, 4, UsageProperties.Rollup.defaults());
        service = new UsagePhysicalSpendService(
                repository,
                new UsageReportExecutor(properties, new SimpleMeterRegistry()),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void adminCannotObtainUnscopedPhysicalSpend() {
        service.findCoverage(null, null, principal("ROLE_ADMIN"));
        verify(repository).findCoverage(NOW.minus(Duration.ofDays(30)), NOW, ORG);
    }

    @Test
    void providerReportUsesEffectiveTenantAndRejectsForeignScope() {
        Instant from = NOW.minusSeconds(60);
        when(repository.findProviders(from, NOW, ORG, "mock", USER)).thenReturn(List.of());
        var response = service.providerReport(from, NOW, null, "mock", USER, principal("ROLE_ADMIN"));
        assertThat(response.organizationId()).isEqualTo(ORG);
        assertThat(response.rows()).isEmpty();
        assertThat(response.aggregationBasis()).isEqualTo("PHYSICAL_ATTEMPT_STARTED_AT");
        assertThatThrownBy(() -> service.providers(from, NOW, USER, null, null, principal("ROLE_ADMIN")))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.providers(from, NOW, null, "x".repeat(101), null,
                principal("ROLE_ADMIN"))).isInstanceOf(BadRequestException.class);
        when(repository.findProviders(from, NOW, null, null, null)).thenReturn(List.of());
        assertThat(service.providerReport(from, NOW, null, null, null, principal("ROLE_SUPER_ADMIN"))
                .organizationId()).isNull();
    }

    @Test
    void superAdminCanReadGlobalPhysicalSpend() {
        service.findCoverage(null, null, principal("ROLE_SUPER_ADMIN"));
        verify(repository).findCoverage(NOW.minus(Duration.ofDays(30)), NOW, null);
    }

    @Test
    void directCallsRejectOrdinaryUserForBothPhysicalReports() {
        var user = principal("ROLE_USER");
        assertThatThrownBy(() -> service.findCoverage(null, null, user))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.reconcile(null, null, user))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidRangeBeforeAnyDatabaseCall() {
        assertThatThrownBy(() -> service.findCoverage(NOW, NOW, principal("ROLE_ADMIN")))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void physicalReportRejectsForeignTenantBeforeQueries() {
        assertThatThrownBy(() -> service.report(null, null, USER, principal("ROLE_ADMIN")))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.report(null, null, null, principal("ROLE_USER")))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void physicalReportPreservesExactCountsAndCostAndSelectedTenant() {
        Instant from = NOW.minus(Duration.ofDays(30));
        when(repository.findCoverage(from, NOW, ORG)).thenReturn(new UsagePhysicalSpendResponse(
                from, NOW, ORG, "PHYSICAL_ATTEMPT_STARTED_AT", "USD",
                2, 0, 1, 1, 0, 0, 1, 1, 9007199254740993L, 0,
                new BigDecimal("0.000000000000000001")));
        when(repository.reconcile(from, NOW, ORG)).thenReturn(new UsagePhysicalReconciliationResponse(
                from, NOW, ORG, "PLANS_WITH_PHYSICAL_START_IN_RANGE", 2, 1, 0, 1));
        var response = service.report(null, null, ORG, principal("ROLE_SUPER_ADMIN"));
        assertThat(response.organizationId()).isEqualTo(ORG);
        assertThat(response.knownInputTokens()).isEqualTo("9007199254740993");
        assertThat(response.knownCostUsd()).isEqualTo("0.000000000000000001");
        assertThat(response.usageComplete()).isFalse();
        assertThat(response.fullyReconciled()).isFalse();
        verify(repository).findCoverage(from, NOW, ORG);
        verify(repository).reconcile(from, NOW, ORG);
    }

    @Test
    void physicalReportDoesNotMixDifferentTenantOrPeriod() {
        Instant from = NOW.minus(Duration.ofDays(30));
        when(repository.findCoverage(from, NOW, ORG)).thenReturn(new UsagePhysicalSpendResponse(
                from, NOW, ORG, "PHYSICAL_ATTEMPT_STARTED_AT", "USD",
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO));
        when(repository.reconcile(from, NOW, ORG)).thenReturn(new UsagePhysicalReconciliationResponse(
                from, NOW, USER, "PLANS_WITH_PHYSICAL_START_IN_RANGE", 0, 0, 0, 0));
        assertThatThrownBy(() -> service.report(null, null, null, principal("ROLE_ADMIN")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope mismatch");
    }

    private static SafeAiUserPrincipal principal(String role) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER, ORG, 0L, 0L, List.of(new SimpleGrantedAuthority(role)));
    }
}

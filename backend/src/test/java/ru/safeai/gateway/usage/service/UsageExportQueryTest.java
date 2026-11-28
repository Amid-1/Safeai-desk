// backend/src/test/java/ru/safeai/gateway/usage/service/UsageExportQueryTest.java
package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.budget.PlatformBudgetClassification;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.dto.*;
import ru.safeai.gateway.usage.repository.UsageQueryRepository;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class UsageExportQueryTest {
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Test
    void everyReportUsesAuthorizedCriteriaAndExistingExecutor() {
        var repository = mock(UsageQueryRepository.class);
        var properties = new UsageProperties(null, null, null, null, null, null, null, null, null);
        var service = new UsageQueryService(repository, mock(UsageRollupStateRepository.class),
                properties, new UsageReportExecutor(properties, new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findSummary(any(), any(), any())).thenReturn(new SliceImpl<>(List.of()));
        when(repository.findUsers(any(), any(), any())).thenReturn(new SliceImpl<>(List.of()));
        when(repository.findOrganizations(any(), any(), any())).thenReturn(new SliceImpl<>(List.of()));
        when(repository.findModels(any(), any())).thenReturn(List.of());
        when(repository.findDaily(any(), any())).thenReturn(List.of());
        for (var kind : List.of(UsageExportRequest.Kind.SUMMARY, UsageExportRequest.Kind.USERS,
                UsageExportRequest.Kind.MODELS, UsageExportRequest.Kind.DAILY)) {
            assertThat(service.exportRows(request(kind, null, null, "cost"), actor("ROLE_ADMIN"), NOW)).isEmpty();
        }
        verify(repository).findSummary(argThat(c -> ORG.equals(c.organizationId())
                        && ACTOR.equals(c.userId()) && "mock".equals(c.model())), any(),
                argThat(p -> p.getPageSize() == 10000 && p.getSort().getOrderFor("cost") != null));
        verify(repository).findUsers(argThat(c -> ORG.equals(c.organizationId())), any(), any());
        verify(repository).findModels(argThat(c -> ORG.equals(c.organizationId())), any());
        verify(repository).findDaily(argThat(c -> ORG.equals(c.organizationId())), any());
        var global = request(UsageExportRequest.Kind.ORGANIZATIONS, null,
                PlatformBudgetClassification.Status.NEAR, "tokens");
        assertThat(service.exportRows(global, actor("ROLE_SUPER_ADMIN"), NOW)).isEmpty();
        verify(repository).findOrganizations(argThat(c -> c.organizationId() == null
                && c.budgetFilter().status() == PlatformBudgetClassification.Status.NEAR), any(), any());
        assertThatThrownBy(() -> service.exportRows(global, actor("ROLE_ADMIN"), NOW))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.exportRows(request(UsageExportRequest.Kind.SUMMARY,
                UUID.randomUUID(), null, "tokens"), actor("ROLE_ADMIN"), NOW))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.exportRows(request(UsageExportRequest.Kind.USERS, null,
                PlatformBudgetClassification.Status.NEAR, "tokens"), actor("ROLE_ADMIN"), NOW))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.exportRows(request(UsageExportRequest.Kind.SUMMARY, null,
                null, "invalid"), actor("ROLE_ADMIN"), NOW)).isInstanceOf(BadRequestException.class);
        when(repository.findSummary(any(), any(), any())).thenReturn(
                new SliceImpl<>(List.of(), PageRequest.of(0, UsageExportCsv.MAX_ROWS), true));
        assertThatThrownBy(() -> service.exportRows(request(UsageExportRequest.Kind.SUMMARY, ORG, null, null),
                actor("ROLE_ADMIN"), NOW)).isInstanceOf(UsageExportCsv.LimitExceeded.class);
    }

    @Test
    void csvEncodesEveryLogicalReportWithoutFloatingPoint() {
        var a = new UsageResponseSummary(0, 0, 0, 0, 0, 0);
        var u = new UsageTokenSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, true);
        var c = new UsageCostSummary(BigDecimal.ZERO, "USD", 0, 0, 0, 0, 0, true);
        assertCsv(UsageExportRequest.Kind.SUMMARY, new UsageSummaryResponse(ACTOR, "test@example.com", "mock",
                a, u, c), "test@example.com");
        assertCsv(UsageExportRequest.Kind.USERS, new UsageUserSummaryResponse(ACTOR, "test@example.com",
                a, u, c), ACTOR.toString());
        assertCsv(UsageExportRequest.Kind.MODELS, new UsageModelSummaryResponse("mock", a, u, c), "mock");
        assertCsv(UsageExportRequest.Kind.DAILY, new UsageDailySummaryResponse(
                LocalDate.of(2026, 11, 21), "UTC", a, u, c), "2026-11-21");
        assertCsv(UsageExportRequest.Kind.ORGANIZATIONS, new UsageOrganizationSummaryResponse(
                ORG, "=formula", a, u, c), "'=formula");
    }

    private static void assertCsv(UsageExportRequest.Kind kind, Object row, String expected) {
        byte[] bytes = UsageExportCsv.encode(request(kind, null, null, null), List.of(row), NOW);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains(expected, "pricing_complete");
    }

    private static UsageExportRequest request(UsageExportRequest.Kind kind, UUID org,
            PlatformBudgetClassification.Status budget, String order) {
        return new UsageExportRequest(kind, NOW.minusSeconds(60), NOW, org, ACTOR, "mock", order, budget);
    }

    private static SafeAiUserPrincipal actor(String role) {
        return SafeAiUserPrincipal.accessTokenPrincipal(ACTOR, ORG, 1, 1,
                List.of(new SimpleGrantedAuthority(role)));
    }
}

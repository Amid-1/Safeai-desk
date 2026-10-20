package ru.safeai.gateway.usage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.repository.UsageQueryCriteria;
import ru.safeai.gateway.usage.repository.UsageQueryPlan;
import ru.safeai.gateway.usage.repository.UsageQueryRepository;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UsageQueryModelFilterTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");

    private UsageQueryRepository repository;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(UsageQueryRepository.class);
        UsageReportExecutor executor = mock(UsageReportExecutor.class);
        when(executor.execute(anyString(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());

        UsageProperties properties = new UsageProperties(
                null, null, Duration.ofDays(366), null, null, null, null, null,
                new UsageProperties.Rollup(false, null, null, null, null, null)
        );
        service = new UsageQueryService(
                repository, mock(UsageRollupStateRepository.class),
                properties, executor, Clock.systemUTC()
        );
    }

    private SafeAiUserPrincipal principal(String authority) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                UUID.randomUUID(), TENANT, 0, 0,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }

    @Test
    void filtersUsersInTenantBeforePaging() {
        when(repository.findUsers(any(), any(), any())).thenReturn(new SliceImpl<>(List.of()));
        service.getUsageByUsers(FROM, TO, "mock-safeai", PageRequest.of(0, 50), principal("ROLE_ADMIN"));
        ArgumentCaptor<UsageQueryCriteria> captured = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        verify(repository).findUsers(captured.capture(), any(UsageQueryPlan.class), any());
        assertThat(captured.getValue().model()).isEqualTo("mock-safeai");
        assertThat(captured.getValue().organizationId()).isEqualTo(TENANT);
    }

    @Test
    void filtersDailyAndModelsByExactModelForAdmin() {
        when(repository.findDaily(any(), any())).thenReturn(List.of());
        when(repository.findModels(any(), any())).thenReturn(List.of());
        var admin = principal("ROLE_ADMIN");
        service.getUsageDaily(FROM, TO, "mock-safeai", admin);
        service.getUsageByModels(FROM, TO, "mock-safeai", admin);
        ArgumentCaptor<UsageQueryCriteria> daily = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        ArgumentCaptor<UsageQueryCriteria> models = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        verify(repository).findDaily(daily.capture(), any());
        verify(repository).findModels(models.capture(), any());
        assertThat(daily.getValue().organizationId()).isEqualTo(TENANT);
        assertThat(models.getValue().organizationId()).isEqualTo(TENANT);
        assertThat(daily.getValue().model()).isEqualTo("mock-safeai");
        assertThat(models.getValue().model()).isEqualTo("mock-safeai");
    }

    @Test
    void unfilteredTenantReportsReuseExistingScopeAndPreserveLegacyBehavior() {
        when(repository.findUsers(any(), any(), any())).thenReturn(new SliceImpl<>(List.of()));
        when(repository.findModels(any(), any())).thenReturn(List.of());
        when(repository.findDaily(any(), any())).thenReturn(List.of());
        SafeAiUserPrincipal admin = principal("ROLE_ADMIN");

        service.getUsageByUsers(FROM, TO, null, PageRequest.of(0, 50), admin);
        service.getUsageByModels(FROM, TO, "   ", admin);
        service.getUsageDaily(FROM, TO, "", admin);

        ArgumentCaptor<UsageQueryCriteria> users = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        ArgumentCaptor<UsageQueryCriteria> models = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        ArgumentCaptor<UsageQueryCriteria> daily = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        verify(repository).findUsers(users.capture(), any(UsageQueryPlan.class), any());
        verify(repository).findModels(models.capture(), any(UsageQueryPlan.class));
        verify(repository).findDaily(daily.capture(), any(UsageQueryPlan.class));

        for (UsageQueryCriteria criteria : List.of(
                users.getValue(), models.getValue(), daily.getValue())) {
            assertThat(criteria.organizationId()).isEqualTo(TENANT);
            assertThat(criteria.model()).isNull();
        }
    }

    @Test
    void unfilteredExplicitOrganizationReportsRetainSelectedScope() {
        when(repository.findUsers(any(), any(), any())).thenReturn(new SliceImpl<>(List.of()));
        when(repository.findModels(any(), any())).thenReturn(List.of());
        when(repository.findDaily(any(), any())).thenReturn(List.of());
        SafeAiUserPrincipal superAdmin = principal("ROLE_SUPER_ADMIN");

        service.getUsageByUsersForOrganization(
                OTHER, FROM, TO, null, PageRequest.of(0, 50), superAdmin);
        service.getUsageByModelsForOrganization(OTHER, FROM, TO, "   ", superAdmin);
        service.getUsageDailyForOrganization(OTHER, FROM, TO, "", superAdmin);

        ArgumentCaptor<UsageQueryCriteria> users = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        ArgumentCaptor<UsageQueryCriteria> models = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        ArgumentCaptor<UsageQueryCriteria> daily = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        verify(repository).findUsers(users.capture(), any(UsageQueryPlan.class), any());
        verify(repository).findModels(models.capture(), any(UsageQueryPlan.class));
        verify(repository).findDaily(daily.capture(), any(UsageQueryPlan.class));

        for (UsageQueryCriteria criteria : List.of(
                users.getValue(), models.getValue(), daily.getValue())) {
            assertThat(criteria.organizationId()).isEqualTo(OTHER);
            assertThat(criteria.model()).isNull();
        }
    }

    @Test
    void absentModelNeverBypassesExplicitTenantAuthorization() {
        SafeAiUserPrincipal admin = principal("ROLE_ADMIN");

        assertThatThrownBy(() -> service.getUsageByUsersForOrganization(
                OTHER, FROM, TO, null, PageRequest.of(0, 50), admin))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.getUsageByModelsForOrganization(
                OTHER, FROM, TO, "   ", admin))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.getUsageDailyForOrganization(
                OTHER, FROM, TO, "", admin))
                .isInstanceOf(ForbiddenOperationException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void tenantAdminCannotFilterAnotherOrganizationsUsage() {
        assertThatThrownBy(() -> service.getUsageDailyForOrganization(
                OTHER, FROM, TO, "mock-safeai", principal("ROLE_ADMIN")))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void superAdminCanExplicitlyScopeModelsToAnotherTenant() {
        when(repository.findModels(any(), any())).thenReturn(List.of());
        service.getUsageByModelsForOrganization(
                OTHER, FROM, TO, "mock-safeai", principal("ROLE_SUPER_ADMIN"));
        ArgumentCaptor<UsageQueryCriteria> captured = ArgumentCaptor.forClass(UsageQueryCriteria.class);
        verify(repository).findModels(captured.capture(), any());
        assertThat(captured.getValue().organizationId()).isEqualTo(OTHER);
        assertThat(captured.getValue().model()).isEqualTo("mock-safeai");
    }
}

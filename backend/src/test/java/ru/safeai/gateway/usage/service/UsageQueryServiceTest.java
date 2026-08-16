package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.repository.UsageInstantRange;
import ru.safeai.gateway.usage.repository.UsageQueryCriteria;
import ru.safeai.gateway.usage.repository.UsageQueryPlan;
import ru.safeai.gateway.usage.repository.UsageQueryRepository;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest.PLATFORM_ORGANIZATION_ID;

@ExtendWith(MockitoExtension.class)
class UsageQueryServiceTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );
    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );
    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );
    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );
    private static final UUID TARGET_USER_ID =
            UUID.fromString(
                    "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
            );

    private static final Instant NOW =
            Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant DATE_FROM =
            Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant DATE_TO =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Pageable PAGEABLE =
            PageRequest.of(0, 50);

    @Mock
    private UsageQueryRepository repository;

    @Mock
    private UsageRollupStateRepository stateRepository;

    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        service = service(defaultProperties());
    }

    @Test
    void adminSummaryIsAlwaysScopedToOwnOrganization() {
        when(repository.findSummary(
                any(),
                any(),
                eq(PAGEABLE)
        )).thenReturn(emptySlice());

        service.getUsageSummary(
                DATE_FROM,
                DATE_TO,
                "  mock-safeai  ",
                PAGEABLE,
                adminPrincipal()
        );

        ArgumentCaptor<UsageQueryCriteria> criteria =
                ArgumentCaptor.forClass(
                        UsageQueryCriteria.class
                );

        verify(repository).findSummary(
                criteria.capture(),
                any(),
                eq(PAGEABLE)
        );

        assertThat(criteria.getValue().organizationId())
                .isEqualTo(ORGANIZATION_ID);
        assertThat(criteria.getValue().userId()).isNull();
        assertThat(criteria.getValue().model())
                .isEqualTo("mock-safeai");
    }

    @Test
    void superAdminSummaryUsesGlobalScope() {
        when(repository.findSummary(
                any(),
                any(),
                eq(PAGEABLE)
        )).thenReturn(emptySlice());

        service.getUsageSummary(
                DATE_FROM,
                DATE_TO,
                null,
                PAGEABLE,
                superAdminPrincipal()
        );

        ArgumentCaptor<UsageQueryCriteria> criteria =
                ArgumentCaptor.forClass(
                        UsageQueryCriteria.class
                );

        verify(repository).findSummary(
                criteria.capture(),
                any(),
                eq(PAGEABLE)
        );

        assertThat(criteria.getValue().organizationId()).isNull();
        assertThat(criteria.getValue().userId()).isNull();
    }

    @Test
    void adminUserReportCombinesUserIdWithTenantScope() {
        when(repository.findSummary(
                any(),
                any(),
                eq(PAGEABLE)
        )).thenReturn(emptySlice());

        service.getUsageByUserId(
                TARGET_USER_ID,
                DATE_FROM,
                DATE_TO,
                null,
                PAGEABLE,
                adminPrincipal()
        );

        ArgumentCaptor<UsageQueryCriteria> criteria =
                ArgumentCaptor.forClass(
                        UsageQueryCriteria.class
                );

        verify(repository).findSummary(
                criteria.capture(),
                any(),
                eq(PAGEABLE)
        );

        assertThat(criteria.getValue().organizationId())
                .isEqualTo(ORGANIZATION_ID);
        assertThat(criteria.getValue().userId())
                .isEqualTo(TARGET_USER_ID);
    }

    @Test
    void adminCannotReadForeignOrganization() {
        assertThatThrownBy(() ->
                service.getUsageByOrganizationId(
                        OTHER_ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        null,
                        PAGEABLE,
                        adminPrincipal()
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining("другой организации");

        verifyNoInteractions(repository);
    }

    @Test
    void superAdminCanReadExplicitForeignOrganization() {
        when(repository.findSummary(
                any(),
                any(),
                eq(PAGEABLE)
        )).thenReturn(emptySlice());

        service.getUsageByOrganizationId(
                OTHER_ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                null,
                PAGEABLE,
                superAdminPrincipal()
        );

        ArgumentCaptor<UsageQueryCriteria> criteria =
                ArgumentCaptor.forClass(
                        UsageQueryCriteria.class
                );

        verify(repository).findSummary(
                criteria.capture(),
                any(),
                eq(PAGEABLE)
        );

        assertThat(criteria.getValue().organizationId())
                .isEqualTo(OTHER_ORGANIZATION_ID);
    }

    @Test
    void defaultRangeUsesInjectedClockExactly() {
        when(repository.findSummary(
                any(),
                any(),
                eq(PAGEABLE)
        )).thenReturn(emptySlice());

        service.getUsageSummary(
                null,
                null,
                null,
                PAGEABLE,
                adminPrincipal()
        );

        ArgumentCaptor<UsageQueryCriteria> criteria =
                ArgumentCaptor.forClass(
                        UsageQueryCriteria.class
                );

        verify(repository).findSummary(
                criteria.capture(),
                any(),
                eq(PAGEABLE)
        );

        assertThat(criteria.getValue().dateTo())
                .isEqualTo(NOW);
        assertThat(criteria.getValue().dateFrom())
                .isEqualTo(NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void rangeLongerThanConfiguredMaximumIsRejected() {
        assertThatThrownBy(() ->
                service.getUsageSummary(
                        DATE_FROM,
                        DATE_FROM.plus(Duration.ofDays(367)),
                        null,
                        PAGEABLE,
                        adminPrincipal()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("366");

        verifyNoInteractions(repository);
    }

    @Test
    void invalidRangeIsRejectedBeforeRepositoryCall() {
        assertThatThrownBy(() ->
                service.getUsageSummary(
                        DATE_TO,
                        DATE_FROM,
                        null,
                        PAGEABLE,
                        adminPrincipal()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dateFrom");

        verifyNoInteractions(repository);
    }

    @Test
    void oversizedAndClientSortedPagesAreRejected() {
        Pageable oversized = PageRequest.of(0, 201);
        Pageable sorted = PageRequest.of(
                0,
                50,
                Sort.by("currentUserEmail")
        );

        assertThatThrownBy(() ->
                service.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        null,
                        oversized,
                        adminPrincipal()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1..200");

        assertThatThrownBy(() ->
                service.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        null,
                        sorted,
                        adminPrincipal()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("сортировка");

        verifyNoInteractions(repository);
    }

    @Test
    void modelLongerThanDatabaseContractIsRejected() {
        assertThatThrownBy(() ->
                service.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        "x".repeat(101),
                        PAGEABLE,
                        adminPrincipal()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("model");

        verifyNoInteractions(repository);
    }

    @Test
    void hybridPlanUsesClosedUtcDaysAndExactLiveEdges() {
        when(stateRepository.findLastCompletedDate())
                .thenReturn(LocalDate.of(2026, 6, 3));
        when(repository.findSummary(
                any(),
                any(),
                eq(PAGEABLE)
        )).thenReturn(emptySlice());

        Instant from =
                Instant.parse("2026-06-01T12:00:00Z");
        Instant to =
                Instant.parse("2026-06-04T12:00:00Z");

        service.getUsageSummary(
                from,
                to,
                null,
                PAGEABLE,
                adminPrincipal()
        );

        ArgumentCaptor<UsageQueryPlan> plan =
                ArgumentCaptor.forClass(
                        UsageQueryPlan.class
                );

        verify(repository).findSummary(
                any(),
                plan.capture(),
                eq(PAGEABLE)
        );

        assertThat(plan.getValue().rollupFrom())
                .isEqualTo(LocalDate.of(2026, 6, 2));
        assertThat(plan.getValue().rollupToExclusive())
                .isEqualTo(LocalDate.of(2026, 6, 4));
        assertThat(plan.getValue().liveRanges())
                .containsExactly(
                        new UsageInstantRange(
                                from,
                                Instant.parse(
                                        "2026-06-02T00:00:00Z"
                                )
                        ),
                        new UsageInstantRange(
                                Instant.parse(
                                        "2026-06-04T00:00:00Z"
                                ),
                                to
                        )
                );
    }

    @Test
    void uncoveredLiveFallbackLongerThanLimitReturns503() {
        when(stateRepository.findLastCompletedDate())
                .thenReturn(null);

        assertThatThrownBy(() ->
                service.getUsageSummary(
                        DATE_FROM,
                        DATE_FROM.plus(Duration.ofDays(32)),
                        null,
                        PAGEABLE,
                        adminPrincipal()
                )
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException response =
                            (ResponseStatusException) exception;
                    assertThat(response.getStatusCode())
                            .isEqualTo(
                                    HttpStatus.SERVICE_UNAVAILABLE
                            );
                })
                .hasMessageContaining("live fallback");

        verify(repository, never()).findSummary(
                any(),
                any(),
                any()
        );
    }

    @Test
    void rollupCanBeDisabledForBoundedLiveQueries() {
        UsageProperties noRollupProperties =
                properties(
                        Duration.ofDays(31),
                        false
                );
        UsageQueryService noRollupService =
                service(noRollupProperties);

        when(repository.findSummary(
                any(),
                any(),
                eq(PAGEABLE)
        )).thenReturn(emptySlice());

        noRollupService.getUsageSummary(
                DATE_FROM,
                DATE_TO,
                null,
                PAGEABLE,
                adminPrincipal()
        );

        ArgumentCaptor<UsageQueryPlan> plan =
                ArgumentCaptor.forClass(
                        UsageQueryPlan.class
                );

        verify(repository).findSummary(
                any(),
                plan.capture(),
                eq(PAGEABLE)
        );

        assertThat(plan.getValue().hasRollupRange()).isFalse();
        assertThat(plan.getValue().liveRanges())
                .containsExactly(
                        new UsageInstantRange(
                                DATE_FROM,
                                DATE_TO
                        )
                );
    }

    @Test
    void nullDependenciesAreRejectedExplicitly() {
        assertThatThrownBy(() ->
                service.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        null,
                        null,
                        adminPrincipal()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pageable");

        assertThatThrownBy(() ->
                service.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        null,
                        PAGEABLE,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currentUser");
    }

    @Test
    void adminDataQualityIsTenantScoped() {
        when(repository.findDataQuality(any(), any()))
                .thenThrow(new TestCaptureException());

        assertThatThrownBy(() -> service.getDataQuality(
                DATE_FROM,
                DATE_TO,
                adminPrincipal()
        ))
                .isInstanceOf(TestCaptureException.class);

        ArgumentCaptor<UsageQueryCriteria> criteria =
                ArgumentCaptor.forClass(UsageQueryCriteria.class);
        verify(repository).findDataQuality(
                criteria.capture(),
                any()
        );
        assertThat(criteria.getValue().organizationId())
                .isEqualTo(ORGANIZATION_ID);
    }

    @Test
    void usersModelsAndDailyUseGlobalScopeForSuperAdmin() {
        when(repository.findUsers(any(), any(), eq(PAGEABLE)))
                .thenReturn(new SliceImpl<>(List.of(), PAGEABLE, false));
        when(repository.findModels(any(), any()))
                .thenReturn(List.of());
        when(repository.findDaily(any(), any()))
                .thenReturn(List.of());

        service.getUsageByUsers(
                DATE_FROM,
                DATE_TO,
                PAGEABLE,
                superAdminPrincipal()
        );
        service.getUsageByModels(
                DATE_FROM,
                DATE_TO,
                superAdminPrincipal()
        );
        service.getUsageDaily(
                DATE_FROM,
                DATE_TO,
                superAdminPrincipal()
        );

        ArgumentCaptor<UsageQueryCriteria> criteria =
                ArgumentCaptor.forClass(UsageQueryCriteria.class);
        verify(repository).findUsers(
                criteria.capture(),
                any(),
                eq(PAGEABLE)
        );
        verify(repository).findModels(criteria.capture(), any());
        verify(repository).findDaily(criteria.capture(), any());

        assertThat(criteria.getAllValues())
                .allSatisfy(value ->
                        assertThat(value.organizationId()).isNull()
                );
    }

    @Test
    void blankModelIsNormalizedToNull() {
        when(repository.findSummary(any(), any(), eq(PAGEABLE)))
                .thenReturn(emptySlice());

        service.getUsageSummary(
                DATE_FROM,
                DATE_TO,
                "   ",
                PAGEABLE,
                adminPrincipal()
        );

        ArgumentCaptor<UsageQueryCriteria> criteria =
                ArgumentCaptor.forClass(UsageQueryCriteria.class);
        verify(repository).findSummary(
                criteria.capture(),
                any(),
                eq(PAGEABLE)
        );
        assertThat(criteria.getValue().model()).isNull();
    }

    @Test
    void exactlyMaximumRangeIsAccepted() {
        Instant to = DATE_FROM.plus(Duration.ofDays(366));
        when(stateRepository.findLastCompletedDate())
                .thenReturn(LocalDate.of(2027, 5, 31));
        when(repository.findSummary(any(), any(), eq(PAGEABLE)))
                .thenReturn(emptySlice());

        service.getUsageSummary(
                DATE_FROM,
                to,
                null,
                PAGEABLE,
                adminPrincipal()
        );

        verify(repository).findSummary(any(), any(), eq(PAGEABLE));
    }

    @Test
    void fullyCoveredWholeUtcDaysUseRollupWithoutLiveFragments() {
        when(stateRepository.findLastCompletedDate())
                .thenReturn(LocalDate.of(2026, 6, 30));
        when(repository.findSummary(any(), any(), eq(PAGEABLE)))
                .thenReturn(emptySlice());

        service.getUsageSummary(
                DATE_FROM,
                DATE_TO,
                null,
                PAGEABLE,
                adminPrincipal()
        );

        ArgumentCaptor<UsageQueryPlan> plan =
                ArgumentCaptor.forClass(UsageQueryPlan.class);
        verify(repository).findSummary(
                any(),
                plan.capture(),
                eq(PAGEABLE)
        );
        assertThat(plan.getValue().rollupFrom())
                .isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(plan.getValue().rollupToExclusive())
                .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(plan.getValue().liveRanges()).isEmpty();
    }

    private static final class TestCaptureException
            extends RuntimeException {
    }

    private UsageQueryService service(
            UsageProperties properties
    ) {
        return new UsageQueryService(
                repository,
                stateRepository,
                properties,
                new UsageReportExecutor(
                        properties,
                        new SimpleMeterRegistry()
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private UsageProperties defaultProperties() {
        return properties(
                Duration.ofDays(31),
                true
        );
    }

    private UsageProperties properties(
            Duration maxLiveRange,
            boolean rollupEnabled
    ) {
        return new UsageProperties(
                Duration.ofDays(30),
                Duration.ofDays(366),
                maxLiveRange,
                200,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                200,
                4,
                new UsageProperties.Rollup(
                        rollupEnabled,
                        "0 */10 * * * *",
                        3,
                        31,
                        8_026_001L,
                        Duration.ofMinutes(2)
                )
        );
    }

    private Slice<UsageSummaryResponse> emptySlice() {
        return new SliceImpl<>(
                List.of(),
                PAGEABLE,
                false
        );
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                0L,
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_SUPER_ADMIN"
                        )
                )
        );
    }
}
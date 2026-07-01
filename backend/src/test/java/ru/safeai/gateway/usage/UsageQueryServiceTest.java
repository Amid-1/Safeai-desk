package ru.safeai.gateway.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.usage.repository.UsageDailySummaryProjection;
import ru.safeai.gateway.usage.repository.UsageQueryRepository;
import ru.safeai.gateway.usage.service.UsageQueryService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsageQueryServiceTest {

    private static final UUID ADMIN_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final Instant DATE_FROM =
            Instant.parse("2026-06-01T00:00:00Z");

    private static final Instant DATE_TO =
            Instant.parse("2026-07-01T00:00:00Z");

    private static final String MODEL = "mock-safeai";

    @Mock
    private UsageQueryRepository usageQueryRepository;

    @InjectMocks
    private UsageQueryService usageQueryService;

    @Test
    void getUsageSummary_whenAdmin_shouldReturnOrganizationScopedResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(userId, "admin@test.com", MODEL, 10L, 20L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL
        )).thenReturn(expected);

        List<UsageSummaryResponse> result =
                usageQueryService.getUsageSummary(DATE_FROM, DATE_TO, MODEL, adminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(30L);

        verify(usageQueryRepository).findUsageByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL
        );
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageSummary_whenSuperAdmin_shouldReturnGlobalResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(userId, "admin@test.com", MODEL, 10L, 20L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageSummary(DATE_FROM, DATE_TO, MODEL))
                .thenReturn(expected);

        List<UsageSummaryResponse> result =
                usageQueryService.getUsageSummary(DATE_FROM, DATE_TO, MODEL, superAdminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(30L);

        verify(usageQueryRepository).findUsageSummary(DATE_FROM, DATE_TO, MODEL);
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageByUsers_whenAdmin_shouldReturnOrganizationScopedResult() {
        UUID userId = UUID.randomUUID();

        List<UsageUserSummaryResponse> expected = List.of(
                new UsageUserSummaryResponse(userId, "admin@test.com", 5L, 15L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageByUsersByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO
        )).thenReturn(expected);

        List<UsageUserSummaryResponse> result =
                usageQueryService.getUsageByUsers(DATE_FROM, DATE_TO, adminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(20L);

        verify(usageQueryRepository).findUsageByUsersByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO
        );
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageByUsers_whenSuperAdmin_shouldReturnGlobalResult() {
        UUID userId = UUID.randomUUID();

        List<UsageUserSummaryResponse> expected = List.of(
                new UsageUserSummaryResponse(userId, "admin@test.com", 5L, 15L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageByUsers(DATE_FROM, DATE_TO))
                .thenReturn(expected);

        List<UsageUserSummaryResponse> result =
                usageQueryService.getUsageByUsers(DATE_FROM, DATE_TO, superAdminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(20L);

        verify(usageQueryRepository).findUsageByUsers(DATE_FROM, DATE_TO);
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageByModels_whenAdmin_shouldReturnOrganizationScopedResult() {
        List<UsageModelSummaryResponse> expected = List.of(
                new UsageModelSummaryResponse(MODEL, 7L, 13L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageByModelsByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO
        )).thenReturn(expected);

        List<UsageModelSummaryResponse> result =
                usageQueryService.getUsageByModels(DATE_FROM, DATE_TO, adminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().model()).isEqualTo(MODEL);
        assertThat(result.getFirst().totalTokens()).isEqualTo(20L);

        verify(usageQueryRepository).findUsageByModelsByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO
        );
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageByModels_whenSuperAdmin_shouldReturnGlobalResult() {
        List<UsageModelSummaryResponse> expected = List.of(
                new UsageModelSummaryResponse(MODEL, 7L, 13L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageByModels(DATE_FROM, DATE_TO))
                .thenReturn(expected);

        List<UsageModelSummaryResponse> result =
                usageQueryService.getUsageByModels(DATE_FROM, DATE_TO, superAdminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().model()).isEqualTo(MODEL);
        assertThat(result.getFirst().totalTokens()).isEqualTo(20L);

        verify(usageQueryRepository).findUsageByModels(DATE_FROM, DATE_TO);
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageDaily_whenAdmin_shouldReturnOrganizationScopedResult() {
        when(usageQueryRepository.findUsageDailyByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO
        )).thenReturn(List.of(usageDailyProjection()));

        List<UsageDailySummaryResponse> result =
                usageQueryService.getUsageDaily(DATE_FROM, DATE_TO, adminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().usageDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(result.getFirst().totalTokens()).isEqualTo(33L);

        verify(usageQueryRepository).findUsageDailyByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO
        );
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageDaily_whenSuperAdmin_shouldReturnGlobalResult() {
        when(usageQueryRepository.findUsageDaily(DATE_FROM, DATE_TO))
                .thenReturn(List.of(usageDailyProjection()));

        List<UsageDailySummaryResponse> result =
                usageQueryService.getUsageDaily(DATE_FROM, DATE_TO, superAdminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().usageDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(result.getFirst().totalTokens()).isEqualTo(33L);

        verify(usageQueryRepository).findUsageDaily(DATE_FROM, DATE_TO);
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageByUserId_whenAdmin_shouldReturnOrganizationScopedResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(userId, "admin@test.com", MODEL, 3L, 4L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageByUserIdAndOrganizationId(
                userId,
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL
        )).thenReturn(expected);

        List<UsageSummaryResponse> result =
                usageQueryService.getUsageByUserId(
                        userId,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        adminPrincipal()
                );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(7L);

        verify(usageQueryRepository).findUsageByUserIdAndOrganizationId(
                userId,
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL
        );
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageByUserId_whenSuperAdmin_shouldReturnGlobalResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(userId, "admin@test.com", MODEL, 3L, 4L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageByUserId(
                userId,
                DATE_FROM,
                DATE_TO,
                MODEL
        )).thenReturn(expected);

        List<UsageSummaryResponse> result =
                usageQueryService.getUsageByUserId(
                        userId,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        superAdminPrincipal()
                );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(7L);

        verify(usageQueryRepository).findUsageByUserId(
                userId,
                DATE_FROM,
                DATE_TO,
                MODEL
        );
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageByOrganizationId_whenAdminRequestsOwnOrganization_shouldReturnRepositoryResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(userId, "admin@test.com", MODEL, 8L, 12L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL
        )).thenReturn(expected);

        List<UsageSummaryResponse> result =
                usageQueryService.getUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        adminPrincipal()
                );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(20L);

        verify(usageQueryRepository).findUsageByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL
        );
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageByOrganizationId_whenAdminRequestsOtherOrganization_shouldThrowForbidden() {
        assertThatThrownBy(() -> usageQueryService.getUsageByOrganizationId(
                OTHER_ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL,
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("Нельзя смотреть usage другой организации");

        verifyNoInteractions(usageQueryRepository);
    }

    @Test
    void getUsageByOrganizationId_whenSuperAdminRequestsAnyOrganization_shouldReturnRepositoryResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(userId, "admin@test.com", MODEL, 8L, 12L, BigDecimal.ZERO)
        );

        when(usageQueryRepository.findUsageByOrganizationId(
                OTHER_ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL
        )).thenReturn(expected);

        List<UsageSummaryResponse> result =
                usageQueryService.getUsageByOrganizationId(
                        OTHER_ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        superAdminPrincipal()
                );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(20L);

        verify(usageQueryRepository).findUsageByOrganizationId(
                OTHER_ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL
        );
        verifyNoMoreInteractions(usageQueryRepository);
    }

    @Test
    void getUsageSummary_whenDateRangeInvalid_shouldThrowBadRequest() {
        assertThatThrownBy(() -> usageQueryService.getUsageSummary(
                DATE_TO,
                DATE_FROM,
                MODEL,
                adminPrincipal()
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dateFrom должен быть раньше dateTo");

        verifyNoInteractions(usageQueryRepository);
    }

    @Test
    void getUsageSummary_whenDateRangeTooLarge_shouldThrowBadRequest() {
        assertThatThrownBy(() -> usageQueryService.getUsageSummary(
                DATE_FROM,
                DATE_FROM.plus(Duration.ofDays(367)),
                MODEL,
                adminPrincipal()
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Период usage не должен превышать 366 дней");

        verifyNoInteractions(usageQueryRepository);
    }

    @Test
    void getUsageSummary_whenDatesAreNull_shouldUseDefaultLast30DaysRange() {
        when(usageQueryRepository.findUsageByOrganizationId(
                eq(ORGANIZATION_ID),
                any(Instant.class),
                any(Instant.class),
                eq(MODEL)
        )).thenReturn(List.of());

        Instant before = Instant.now();

        usageQueryService.getUsageSummary(
                null,
                null,
                MODEL,
                adminPrincipal()
        );

        Instant after = Instant.now();

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);

        verify(usageQueryRepository).findUsageByOrganizationId(
                eq(ORGANIZATION_ID),
                fromCaptor.capture(),
                toCaptor.capture(),
                eq(MODEL)
        );

        Instant from = fromCaptor.getValue();
        Instant to = toCaptor.getValue();

        assertThat(to).isBetween(before, after);
        assertThat(Duration.between(from, to).toDays()).isEqualTo(30);
    }

    private UsageDailySummaryProjection usageDailyProjection() {
        return new UsageDailySummaryProjection() {
            @Override
            public LocalDate getUsageDate() {
                return LocalDate.of(2026, 6, 12);
            }

            @Override
            public Long getInputTokens() {
                return 11L;
            }

            @Override
            public Long getOutputTokens() {
                return 22L;
            }

            @Override
            public BigDecimal getCostUsd() {
                return BigDecimal.ZERO;
            }
        };
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return new SafeAiUserPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return new SafeAiUserPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "superadmin@test.com",
                "",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );
    }
}
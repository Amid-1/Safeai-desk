package ru.safeai.gateway.usage.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageQueryServiceTest {

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000101"
            );

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final UUID TARGET_USER_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    private static final Instant DATE_FROM =
            Instant.parse("2026-06-01T00:00:00Z");

    private static final Instant DATE_TO =
            Instant.parse("2026-07-01T00:00:00Z");

    private static final String MODEL =
            "mock-safeai";

    private static final Pageable PAGEABLE =
            PageRequest.of(0, 50);

    @Mock
    private UsageQueryRepository usageQueryRepository;

    @InjectMocks
    private UsageQueryService usageQueryService;

    @Test
    void summaryUsesOrganizationScopeForAdmin() {
        Slice<UsageSummaryResponse> expected =
                usageSummarySlice();

        when(usageQueryRepository.findUsageByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL,
                PAGEABLE
        )).thenReturn(expected);

        Slice<UsageSummaryResponse> result =
                usageQueryService.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE,
                        adminPrincipal()
                );

        assertThat(result).isSameAs(expected);
        assertThat(result.getContent())
                .containsExactlyElementsOf(expected.getContent());

        verify(usageQueryRepository)
                .findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE
                );
    }

    @Test
    void summaryUsesGlobalQueryForSuperAdmin() {
        Slice<UsageSummaryResponse> expected =
                usageSummarySlice();

        when(usageQueryRepository.findUsageSummary(
                DATE_FROM,
                DATE_TO,
                MODEL,
                PAGEABLE
        )).thenReturn(expected);

        Slice<UsageSummaryResponse> result =
                usageQueryService.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE,
                        superAdminPrincipal()
                );

        assertThat(result.getContent()).hasSize(1);

        verify(usageQueryRepository).findUsageSummary(
                DATE_FROM,
                DATE_TO,
                MODEL,
                PAGEABLE
        );
    }

    @Test
    void usersUseTenantAndGlobalQueries() {
        Slice<UsageUserSummaryResponse> expected =
                usageUserSummarySlice();

        when(
                usageQueryRepository
                        .findUsageByUsersByOrganizationId(
                                ORGANIZATION_ID,
                                DATE_FROM,
                                DATE_TO,
                                PAGEABLE
                        )
        ).thenReturn(expected);

        when(usageQueryRepository.findUsageByUsers(
                DATE_FROM,
                DATE_TO,
                PAGEABLE
        )).thenReturn(expected);

        Slice<UsageUserSummaryResponse> adminResult =
                usageQueryService.getUsageByUsers(
                        DATE_FROM,
                        DATE_TO,
                        PAGEABLE,
                        adminPrincipal()
                );

        Slice<UsageUserSummaryResponse> superAdminResult =
                usageQueryService.getUsageByUsers(
                        DATE_FROM,
                        DATE_TO,
                        PAGEABLE,
                        superAdminPrincipal()
                );

        assertThat(adminResult).isSameAs(expected);
        assertThat(superAdminResult).isSameAs(expected);

        verify(usageQueryRepository)
                .findUsageByUsersByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        PAGEABLE
                );

        verify(usageQueryRepository).findUsageByUsers(
                DATE_FROM,
                DATE_TO,
                PAGEABLE
        );
    }

    @Test
    void modelsUseTenantAndGlobalQueries() {
        List<UsageModelSummaryResponse> expected =
                List.of(
                        new UsageModelSummaryResponse(
                                MODEL,
                                7L,
                                13L,
                                BigDecimal.ZERO
                        )
                );

        when(
                usageQueryRepository
                        .findUsageByModelsByOrganizationId(
                                ORGANIZATION_ID,
                                DATE_FROM,
                                DATE_TO
                        )
        ).thenReturn(expected);

        when(usageQueryRepository.findUsageByModels(
                DATE_FROM,
                DATE_TO
        )).thenReturn(expected);

        assertThat(
                usageQueryService.getUsageByModels(
                        DATE_FROM,
                        DATE_TO,
                        adminPrincipal()
                )
        ).isEqualTo(expected);

        assertThat(
                usageQueryService.getUsageByModels(
                        DATE_FROM,
                        DATE_TO,
                        superAdminPrincipal()
                )
        ).isEqualTo(expected);
    }

    @Test
    void dailyProjectionIsMappedForAdmin() {
        when(
                usageQueryRepository
                        .findUsageDailyByOrganizationId(
                                ORGANIZATION_ID,
                                DATE_FROM,
                                DATE_TO
                        )
        ).thenReturn(
                List.of(dailyProjection())
        );

        List<UsageDailySummaryResponse> result =
                usageQueryService.getUsageDaily(
                        DATE_FROM,
                        DATE_TO,
                        adminPrincipal()
                );

        assertThat(result)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.usageDate())
                            .isEqualTo(
                                    LocalDate.of(
                                            2026,
                                            6,
                                            12
                                    )
                            );

                    assertThat(item.inputTokens())
                            .isEqualTo(11L);

                    assertThat(item.outputTokens())
                            .isEqualTo(22L);

                    assertThat(item.totalTokens())
                            .isEqualTo(33L);

                    assertThat(item.costUsd())
                            .isEqualByComparingTo(
                                    BigDecimal.ZERO
                            );
                });
    }

    @Test
    void dailyProjectionUsesGlobalQueryForSuperAdmin() {
        when(usageQueryRepository.findUsageDaily(
                DATE_FROM,
                DATE_TO
        )).thenReturn(
                List.of(dailyProjection())
        );

        List<UsageDailySummaryResponse> result =
                usageQueryService.getUsageDaily(
                        DATE_FROM,
                        DATE_TO,
                        superAdminPrincipal()
                );

        assertThat(result).hasSize(1);

        verify(usageQueryRepository).findUsageDaily(
                DATE_FROM,
                DATE_TO
        );
    }

    @Test
    void userIdUsesTenantSafeQueryForAdminAndGlobalForSuperAdmin() {
        Slice<UsageSummaryResponse> expected =
                usageSummarySlice();

        when(
                usageQueryRepository
                        .findUsageByUserIdAndOrganizationId(
                                TARGET_USER_ID,
                                ORGANIZATION_ID,
                                DATE_FROM,
                                DATE_TO,
                                MODEL,
                                PAGEABLE
                        )
        ).thenReturn(expected);

        when(usageQueryRepository.findUsageByUserId(
                TARGET_USER_ID,
                DATE_FROM,
                DATE_TO,
                MODEL,
                PAGEABLE
        )).thenReturn(expected);

        Slice<UsageSummaryResponse> adminResult =
                usageQueryService.getUsageByUserId(
                        TARGET_USER_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE,
                        adminPrincipal()
                );

        Slice<UsageSummaryResponse> superAdminResult =
                usageQueryService.getUsageByUserId(
                        TARGET_USER_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE,
                        superAdminPrincipal()
                );

        assertThat(adminResult.getContent()).hasSize(1);
        assertThat(superAdminResult.getContent()).hasSize(1);

        verify(usageQueryRepository)
                .findUsageByUserIdAndOrganizationId(
                        TARGET_USER_ID,
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE
                );

        verify(usageQueryRepository).findUsageByUserId(
                TARGET_USER_ID,
                DATE_FROM,
                DATE_TO,
                MODEL,
                PAGEABLE
        );
    }

    @Test
    void adminCanReadOwnOrganization() {
        Slice<UsageSummaryResponse> expected =
                usageSummarySlice();

        when(usageQueryRepository.findUsageByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL,
                PAGEABLE
        )).thenReturn(expected);

        Slice<UsageSummaryResponse> result =
                usageQueryService.getUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE,
                        adminPrincipal()
                );

        assertThat(result).isSameAs(expected);

        verify(usageQueryRepository)
                .findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE
                );
    }

    @Test
    void superAdminCanReadAnyOrganization() {
        Slice<UsageSummaryResponse> expected =
                usageSummarySlice();

        when(usageQueryRepository.findUsageByOrganizationId(
                OTHER_ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL,
                PAGEABLE
        )).thenReturn(expected);

        Slice<UsageSummaryResponse> result =
                usageQueryService.getUsageByOrganizationId(
                        OTHER_ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE,
                        superAdminPrincipal()
                );

        assertThat(result).isSameAs(expected);
    }

    @Test
    void adminCannotReadForeignOrganization() {
        assertThatThrownBy(() ->
                usageQueryService.getUsageByOrganizationId(
                        OTHER_ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE,
                        adminPrincipal()
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessage(
                        "Нельзя смотреть usage другой организации"
                );

        verifyNoInteractions(usageQueryRepository);
    }

    @Test
    void invalidAndTooLargeRangesAreRejected() {
        assertThatThrownBy(() ->
                usageQueryService.getUsageSummary(
                        DATE_TO,
                        DATE_FROM,
                        MODEL,
                        PAGEABLE,
                        adminPrincipal()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dateFrom");

        assertThatThrownBy(() ->
                usageQueryService.getUsageSummary(
                        DATE_FROM,
                        DATE_FROM.plus(
                                Duration.ofDays(367)
                        ),
                        MODEL,
                        PAGEABLE,
                        adminPrincipal()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("366");

        verifyNoInteractions(usageQueryRepository);
    }

    @Test
    void blankModelIsNormalizedToNull() {
        when(usageQueryRepository.findUsageByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                null,
                PAGEABLE
        )).thenReturn(emptySummarySlice());

        usageQueryService.getUsageSummary(
                DATE_FROM,
                DATE_TO,
                "   ",
                PAGEABLE,
                adminPrincipal()
        );

        verify(usageQueryRepository)
                .findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        null,
                        PAGEABLE
                );
    }

    @Test
    void modelIsTrimmedBeforeRepositoryCall() {
        when(usageQueryRepository.findUsageByOrganizationId(
                ORGANIZATION_ID,
                DATE_FROM,
                DATE_TO,
                MODEL,
                PAGEABLE
        )).thenReturn(emptySummarySlice());

        usageQueryService.getUsageSummary(
                DATE_FROM,
                DATE_TO,
                "  " + MODEL + "  ",
                PAGEABLE,
                adminPrincipal()
        );

        verify(usageQueryRepository)
                .findUsageByOrganizationId(
                        ORGANIZATION_ID,
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE
                );
    }

    @Test
    void tooLongModelIsRejected() {
        assertThatThrownBy(() ->
                usageQueryService.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        "a".repeat(101),
                        PAGEABLE,
                        adminPrincipal()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("model");

        verifyNoInteractions(usageQueryRepository);
    }

    @Test
    void nullDatesUseThirtyDayRange() {
        when(usageQueryRepository.findUsageByOrganizationId(
                eq(ORGANIZATION_ID),
                any(Instant.class),
                any(Instant.class),
                eq(MODEL),
                eq(PAGEABLE)
        )).thenReturn(emptySummarySlice());

        Instant before = Instant.now();

        usageQueryService.getUsageSummary(
                null,
                null,
                MODEL,
                PAGEABLE,
                adminPrincipal()
        );

        Instant after = Instant.now();

        ArgumentCaptor<Instant> fromCaptor =
                ArgumentCaptor.forClass(
                        Instant.class
                );

        ArgumentCaptor<Instant> toCaptor =
                ArgumentCaptor.forClass(
                        Instant.class
                );

        verify(usageQueryRepository)
                .findUsageByOrganizationId(
                        eq(ORGANIZATION_ID),
                        fromCaptor.capture(),
                        toCaptor.capture(),
                        eq(MODEL),
                        eq(PAGEABLE)
                );

        assertThat(toCaptor.getValue())
                .isBetween(before, after);

        assertThat(
                Duration.between(
                        fromCaptor.getValue(),
                        toCaptor.getValue()
                )
        ).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void nullPageableIsRejected() {
        assertThatThrownBy(() ->
                usageQueryService.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        null,
                        adminPrincipal()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pageable");

        verifyNoInteractions(usageQueryRepository);
    }

    @Test
    void nullCurrentUserIsRejected() {
        assertThatThrownBy(() ->
                usageQueryService.getUsageSummary(
                        DATE_FROM,
                        DATE_TO,
                        MODEL,
                        PAGEABLE,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currentUser");

        verifyNoInteractions(usageQueryRepository);
    }

    private Slice<UsageSummaryResponse> usageSummarySlice() {
        return new SliceImpl<>(
                List.of(
                        new UsageSummaryResponse(
                                TARGET_USER_ID,
                                "user@test.com",
                                MODEL,
                                10L,
                                20L,
                                BigDecimal.ZERO
                        )
                ),
                PAGEABLE,
                false
        );
    }

    private Slice<UsageSummaryResponse> emptySummarySlice() {
        return new SliceImpl<>(
                List.of(),
                PAGEABLE,
                false
        );
    }

    private Slice<UsageUserSummaryResponse> usageUserSummarySlice() {
        return new SliceImpl<>(
                List.of(
                        new UsageUserSummaryResponse(
                                TARGET_USER_ID,
                                "user@test.com",
                                5L,
                                15L,
                                BigDecimal.ZERO
                        )
                ),
                PAGEABLE,
                false
        );
    }

    private UsageDailySummaryProjection dailyProjection() {
        return new UsageDailySummaryProjection() {

            @Override
            public LocalDate getUsageDate() {
                return LocalDate.of(
                        2026,
                        6,
                        12
                );
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
        return principal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "ROLE_ADMIN"
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return principal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "ROLE_SUPER_ADMIN"
        );
    }

    private SafeAiUserPrincipal principal(
            UUID userId,
            UUID organizationId,
            String role
    ) {
        return new SafeAiUserPrincipal(
                userId,
                organizationId,
                "user@test.com",
                "",
                true,
                0L,
                List.of(
                        new SimpleGrantedAuthority(role)
                )
        );
    }
}
package ru.safeai.gateway.admin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.chat.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.chat.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.UsageDailySummaryProjection;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUsageServiceTest {

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

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @InjectMocks
    private AdminUsageService adminUsageService;

    @Test
    void getUsageSummary_whenAdmin_shouldReturnOrganizationScopedResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(
                        userId,
                        "admin@test.com",
                        "mock-safeai",
                        10L,
                        20L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByOrganizationId(ORGANIZATION_ID))
                .thenReturn(expected);

        List<UsageSummaryResponse> result =
                adminUsageService.getUsageSummary(adminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(userId);
        assertThat(result.get(0).userEmail()).isEqualTo("admin@test.com");
        assertThat(result.get(0).model()).isEqualTo("mock-safeai");
        assertThat(result.get(0).inputTokens()).isEqualTo(10L);
        assertThat(result.get(0).outputTokens()).isEqualTo(20L);
        assertThat(result.get(0).totalTokens()).isEqualTo(30L);
        assertThat(result.get(0).costUsd()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(chatMessageRepository).findUsageByOrganizationId(ORGANIZATION_ID);
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageSummary_whenSuperAdmin_shouldReturnGlobalResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(
                        userId,
                        "admin@test.com",
                        "mock-safeai",
                        10L,
                        20L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageSummary()).thenReturn(expected);

        List<UsageSummaryResponse> result =
                adminUsageService.getUsageSummary(superAdminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(userId);
        assertThat(result.get(0).totalTokens()).isEqualTo(30L);

        verify(chatMessageRepository).findUsageSummary();
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByUsers_whenAdmin_shouldReturnOrganizationScopedResult() {
        UUID userId = UUID.randomUUID();

        List<UsageUserSummaryResponse> expected = List.of(
                new UsageUserSummaryResponse(
                        userId,
                        "admin@test.com",
                        5L,
                        15L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByUsersByOrganizationId(ORGANIZATION_ID))
                .thenReturn(expected);

        List<UsageUserSummaryResponse> result =
                adminUsageService.getUsageByUsers(adminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(userId);
        assertThat(result.get(0).userEmail()).isEqualTo("admin@test.com");
        assertThat(result.get(0).inputTokens()).isEqualTo(5L);
        assertThat(result.get(0).outputTokens()).isEqualTo(15L);
        assertThat(result.get(0).totalTokens()).isEqualTo(20L);

        verify(chatMessageRepository).findUsageByUsersByOrganizationId(ORGANIZATION_ID);
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByUsers_whenSuperAdmin_shouldReturnGlobalResult() {
        UUID userId = UUID.randomUUID();

        List<UsageUserSummaryResponse> expected = List.of(
                new UsageUserSummaryResponse(
                        userId,
                        "admin@test.com",
                        5L,
                        15L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByUsers()).thenReturn(expected);

        List<UsageUserSummaryResponse> result =
                adminUsageService.getUsageByUsers(superAdminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(userId);
        assertThat(result.get(0).totalTokens()).isEqualTo(20L);

        verify(chatMessageRepository).findUsageByUsers();
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByModels_whenAdmin_shouldReturnOrganizationScopedResult() {
        List<UsageModelSummaryResponse> expected = List.of(
                new UsageModelSummaryResponse(
                        "mock-safeai",
                        7L,
                        13L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByModelsByOrganizationId(ORGANIZATION_ID))
                .thenReturn(expected);

        List<UsageModelSummaryResponse> result =
                adminUsageService.getUsageByModels(adminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).model()).isEqualTo("mock-safeai");
        assertThat(result.get(0).inputTokens()).isEqualTo(7L);
        assertThat(result.get(0).outputTokens()).isEqualTo(13L);
        assertThat(result.get(0).totalTokens()).isEqualTo(20L);

        verify(chatMessageRepository).findUsageByModelsByOrganizationId(ORGANIZATION_ID);
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByModels_whenSuperAdmin_shouldReturnGlobalResult() {
        List<UsageModelSummaryResponse> expected = List.of(
                new UsageModelSummaryResponse(
                        "mock-safeai",
                        7L,
                        13L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByModels()).thenReturn(expected);

        List<UsageModelSummaryResponse> result =
                adminUsageService.getUsageByModels(superAdminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).model()).isEqualTo("mock-safeai");
        assertThat(result.get(0).totalTokens()).isEqualTo(20L);

        verify(chatMessageRepository).findUsageByModels();
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageDaily_whenAdmin_shouldReturnOrganizationScopedResult() {
        UsageDailySummaryProjection projection = usageDailyProjection();

        when(chatMessageRepository.findUsageDailyByOrganizationId(ORGANIZATION_ID))
                .thenReturn(List.of(projection));

        List<UsageDailySummaryResponse> result =
                adminUsageService.getUsageDaily(adminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).usageDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(result.get(0).inputTokens()).isEqualTo(11L);
        assertThat(result.get(0).outputTokens()).isEqualTo(22L);
        assertThat(result.get(0).totalTokens()).isEqualTo(33L);
        assertThat(result.get(0).costUsd()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(chatMessageRepository).findUsageDailyByOrganizationId(ORGANIZATION_ID);
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageDaily_whenSuperAdmin_shouldReturnGlobalResult() {
        UsageDailySummaryProjection projection = usageDailyProjection();

        when(chatMessageRepository.findUsageDaily())
                .thenReturn(List.of(projection));

        List<UsageDailySummaryResponse> result =
                adminUsageService.getUsageDaily(superAdminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).usageDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(result.get(0).totalTokens()).isEqualTo(33L);

        verify(chatMessageRepository).findUsageDaily();
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByUserId_whenAdmin_shouldReturnOrganizationScopedResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(
                        userId,
                        "admin@test.com",
                        "mock-safeai",
                        3L,
                        4L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByUserIdAndOrganizationId(
                userId,
                ORGANIZATION_ID
        )).thenReturn(expected);

        List<UsageSummaryResponse> result =
                adminUsageService.getUsageByUserId(userId, adminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(userId);
        assertThat(result.get(0).totalTokens()).isEqualTo(7L);

        verify(chatMessageRepository).findUsageByUserIdAndOrganizationId(
                userId,
                ORGANIZATION_ID
        );
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByUserId_whenSuperAdmin_shouldReturnGlobalResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(
                        userId,
                        "admin@test.com",
                        "mock-safeai",
                        3L,
                        4L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByUserId(userId))
                .thenReturn(expected);

        List<UsageSummaryResponse> result =
                adminUsageService.getUsageByUserId(userId, superAdminPrincipal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(userId);
        assertThat(result.get(0).totalTokens()).isEqualTo(7L);

        verify(chatMessageRepository).findUsageByUserId(userId);
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByOrganizationId_whenAdminRequestsOwnOrganization_shouldReturnRepositoryResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(
                        userId,
                        "admin@test.com",
                        "mock-safeai",
                        8L,
                        12L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByOrganizationId(ORGANIZATION_ID))
                .thenReturn(expected);

        List<UsageSummaryResponse> result =
                adminUsageService.getUsageByOrganizationId(
                        ORGANIZATION_ID,
                        adminPrincipal()
                );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(userId);
        assertThat(result.get(0).totalTokens()).isEqualTo(20L);

        verify(chatMessageRepository).findUsageByOrganizationId(ORGANIZATION_ID);
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByOrganizationId_whenAdminRequestsOtherOrganization_shouldThrowForbidden() {
        assertThatThrownBy(() -> adminUsageService.getUsageByOrganizationId(
                OTHER_ORGANIZATION_ID,
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("Нельзя смотреть usage другой организации");

        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByOrganizationId_whenSuperAdminRequestsAnyOrganization_shouldReturnRepositoryResult() {
        UUID userId = UUID.randomUUID();

        List<UsageSummaryResponse> expected = List.of(
                new UsageSummaryResponse(
                        userId,
                        "admin@test.com",
                        "mock-safeai",
                        8L,
                        12L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByOrganizationId(OTHER_ORGANIZATION_ID))
                .thenReturn(expected);

        List<UsageSummaryResponse> result =
                adminUsageService.getUsageByOrganizationId(
                        OTHER_ORGANIZATION_ID,
                        superAdminPrincipal()
                );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(userId);
        assertThat(result.get(0).totalTokens()).isEqualTo(20L);

        verify(chatMessageRepository).findUsageByOrganizationId(OTHER_ORGANIZATION_ID);
        verifyNoMoreInteractions(chatMessageRepository);
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
package ru.safeai.gateway.admin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.chat.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.chat.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.chat.repository.UsageDailySummaryProjection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUsageServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @InjectMocks
    private AdminUsageService adminUsageService;

    @Test
    void getUsageSummary_shouldReturnRepositoryResult() {
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

        List<UsageSummaryResponse> result = adminUsageService.getUsageSummary();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().userEmail()).isEqualTo("admin@test.com");
        assertThat(result.getFirst().model()).isEqualTo("mock-safeai");
        assertThat(result.getFirst().inputTokens()).isEqualTo(10L);
        assertThat(result.getFirst().outputTokens()).isEqualTo(20L);
        assertThat(result.getFirst().totalTokens()).isEqualTo(30L);
        assertThat(result.getFirst().costUsd()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(chatMessageRepository).findUsageSummary();
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByUsers_shouldReturnRepositoryResult() {
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

        List<UsageUserSummaryResponse> result = adminUsageService.getUsageByUsers();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().userEmail()).isEqualTo("admin@test.com");
        assertThat(result.getFirst().inputTokens()).isEqualTo(5L);
        assertThat(result.getFirst().outputTokens()).isEqualTo(15L);
        assertThat(result.getFirst().totalTokens()).isEqualTo(20L);

        verify(chatMessageRepository).findUsageByUsers();
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByModels_shouldReturnRepositoryResult() {
        List<UsageModelSummaryResponse> expected = List.of(
                new UsageModelSummaryResponse(
                        "mock-safeai",
                        7L,
                        13L,
                        BigDecimal.ZERO
                )
        );

        when(chatMessageRepository.findUsageByModels()).thenReturn(expected);

        List<UsageModelSummaryResponse> result = adminUsageService.getUsageByModels();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().model()).isEqualTo("mock-safeai");
        assertThat(result.getFirst().inputTokens()).isEqualTo(7L);
        assertThat(result.getFirst().outputTokens()).isEqualTo(13L);
        assertThat(result.getFirst().totalTokens()).isEqualTo(20L);

        verify(chatMessageRepository).findUsageByModels();
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageDaily_shouldMapProjectionToResponse() {
        UsageDailySummaryProjection projection = new UsageDailySummaryProjection() {
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

        when(chatMessageRepository.findUsageDaily()).thenReturn(List.of(projection));

        List<UsageDailySummaryResponse> result = adminUsageService.getUsageDaily();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().usageDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(result.getFirst().inputTokens()).isEqualTo(11L);
        assertThat(result.getFirst().outputTokens()).isEqualTo(22L);
        assertThat(result.getFirst().totalTokens()).isEqualTo(33L);
        assertThat(result.getFirst().costUsd()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(chatMessageRepository).findUsageDaily();
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByUserId_shouldReturnRepositoryResult() {
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

        when(chatMessageRepository.findUsageByUserId(userId)).thenReturn(expected);

        List<UsageSummaryResponse> result = adminUsageService.getUsageByUserId(userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(7L);

        verify(chatMessageRepository).findUsageByUserId(userId);
        verifyNoMoreInteractions(chatMessageRepository);
    }

    @Test
    void getUsageByOrganizationId_shouldReturnRepositoryResult() {
        UUID organizationId = UUID.randomUUID();
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

        when(chatMessageRepository.findUsageByOrganizationId(organizationId)).thenReturn(expected);

        List<UsageSummaryResponse> result = adminUsageService.getUsageByOrganizationId(organizationId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().totalTokens()).isEqualTo(20L);

        verify(chatMessageRepository).findUsageByOrganizationId(organizationId);
        verifyNoMoreInteractions(chatMessageRepository);
    }
}
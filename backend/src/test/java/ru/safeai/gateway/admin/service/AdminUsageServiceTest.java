package ru.safeai.gateway.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUsageServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private AdminUsageService adminUsageService;

    @BeforeEach
    void setUp() {
        adminUsageService = new AdminUsageService(chatMessageRepository);
    }

    @Test
    void getUsageSummary_shouldReturnAggregatedUsage() {
        UUID userId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        UsageSummaryResponse summary = new UsageSummaryResponse(
                userId,
                "admin@test.com",
                "mock-safeai",
                25L,
                40L,
                65L,
                BigDecimal.ZERO
        );

        when(chatMessageRepository.findUsageSummary())
                .thenReturn(List.of(summary));

        List<UsageSummaryResponse> result = adminUsageService.getUsageSummary();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().userId()).isEqualTo(userId);
        assertThat(result.getFirst().userEmail()).isEqualTo("admin@test.com");
        assertThat(result.getFirst().model()).isEqualTo("mock-safeai");
        assertThat(result.getFirst().inputTokens()).isEqualTo(25L);
        assertThat(result.getFirst().outputTokens()).isEqualTo(40L);
        assertThat(result.getFirst().totalTokens()).isEqualTo(65L);
        assertThat(result.getFirst().costUsd()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(chatMessageRepository).findUsageSummary();
    }
}
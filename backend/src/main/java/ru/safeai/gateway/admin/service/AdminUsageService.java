package ru.safeai.gateway.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.chat.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUsageService {

    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageSummary() {
        return chatMessageRepository.findUsageSummary();
    }

    @Transactional(readOnly = true)
    public List<UsageUserSummaryResponse> getUsageByUsers() {
        return chatMessageRepository.findUsageByUsers();
    }

    @Transactional(readOnly = true)
    public List<UsageModelSummaryResponse> getUsageByModels() {
        return chatMessageRepository.findUsageByModels();
    }

    @Transactional(readOnly = true)
    public List<UsageDailySummaryResponse> getUsageDaily() {
        return chatMessageRepository.findUsageDaily()
                .stream()
                .map(row -> new UsageDailySummaryResponse(
                        row.getUsageDate(),
                        row.getInputTokens(),
                        row.getOutputTokens(),
                        row.getCostUsd()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageByUserId(UUID userId) {
        return chatMessageRepository.findUsageByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageByOrganizationId(UUID organizationId) {
        return chatMessageRepository.findUsageByOrganizationId(organizationId);
    }
}
package ru.safeai.gateway.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.chat.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.chat.repository.ChatMessageRepository;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

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
    public List<UsageSummaryResponse> getUsageByOrganizationId(
            UUID organizationId,
            SafeAiUserPrincipal currentUser
    ) {
        if (!isSuperAdmin(currentUser) && !currentUser.getOrganizationId().equals(organizationId)) {
            throw new ForbiddenOperationException("Нельзя смотреть usage другой организации");
        }

        return chatMessageRepository.findUsageByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageSummary(SafeAiUserPrincipal currentUser) {
        if (isSuperAdmin(currentUser)) {
            return chatMessageRepository.findUsageSummary();
        }

        return chatMessageRepository.findUsageByOrganizationId(currentUser.getOrganizationId());
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal currentUser) {
        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }
}
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
import ru.safeai.gateway.chat.repository.UsageDailySummaryProjection;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUsageService {

    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageSummary(
            SafeAiUserPrincipal currentUser
    ) {
        if (isSuperAdmin(currentUser)) {
            return chatMessageRepository.findUsageSummary();
        }

        return chatMessageRepository.findUsageByOrganizationId(
                currentUser.getOrganizationId()
        );
    }

    @Transactional(readOnly = true)
    public List<UsageUserSummaryResponse> getUsageByUsers(
            SafeAiUserPrincipal currentUser
    ) {
        if (isSuperAdmin(currentUser)) {
            return chatMessageRepository.findUsageByUsers();
        }

        return chatMessageRepository.findUsageByUsersByOrganizationId(
                currentUser.getOrganizationId()
        );
    }

    @Transactional(readOnly = true)
    public List<UsageModelSummaryResponse> getUsageByModels(
            SafeAiUserPrincipal currentUser
    ) {
        if (isSuperAdmin(currentUser)) {
            return chatMessageRepository.findUsageByModels();
        }

        return chatMessageRepository.findUsageByModelsByOrganizationId(
                currentUser.getOrganizationId()
        );
    }

    @Transactional(readOnly = true)
    public List<UsageDailySummaryResponse> getUsageDaily(
            SafeAiUserPrincipal currentUser
    ) {
        List<UsageDailySummaryProjection> rows = isSuperAdmin(currentUser)
                ? chatMessageRepository.findUsageDaily()
                : chatMessageRepository.findUsageDailyByOrganizationId(
                currentUser.getOrganizationId()
        );

        return rows.stream()
                .map(row -> new UsageDailySummaryResponse(
                        row.getUsageDate(),
                        row.getInputTokens(),
                        row.getOutputTokens(),
                        row.getCostUsd()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageByUserId(
            UUID userId,
            SafeAiUserPrincipal currentUser
    ) {
        if (isSuperAdmin(currentUser)) {
            return chatMessageRepository.findUsageByUserId(userId);
        }

        return chatMessageRepository.findUsageByUserIdAndOrganizationId(
                userId,
                currentUser.getOrganizationId()
        );
    }

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageByOrganizationId(
            UUID organizationId,
            SafeAiUserPrincipal currentUser
    ) {
        if (!isSuperAdmin(currentUser)
                && !currentUser.getOrganizationId().equals(organizationId)) {
            throw new ForbiddenOperationException(
                    "Нельзя смотреть usage другой организации"
            );
        }

        return chatMessageRepository.findUsageByOrganizationId(organizationId);
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal currentUser) {
        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }
}
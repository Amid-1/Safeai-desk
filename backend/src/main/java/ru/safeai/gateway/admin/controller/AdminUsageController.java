package ru.safeai.gateway.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.admin.service.AdminUsageService;
import ru.safeai.gateway.chat.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.chat.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUsageController {

    private final AdminUsageService adminUsageService;

    /**
     * Старый endpoint оставляем, чтобы не сломать текущий frontend.
     */
    @GetMapping("/usage-summary")
    public List<UsageSummaryResponse> getOldUsageSummary(
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return adminUsageService.getUsageSummary(currentUser);
    }

    /**
     * Новый нормальный endpoint.
     */
    @GetMapping("/usage/summary")
    public List<UsageSummaryResponse> getUsageSummary(
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return adminUsageService.getUsageSummary(currentUser);
    }

    @GetMapping("/usage/users")
    public List<UsageUserSummaryResponse> getUsageByUsers() {
        return adminUsageService.getUsageByUsers();
    }

    @GetMapping("/usage/models")
    public List<UsageModelSummaryResponse> getUsageByModels() {
        return adminUsageService.getUsageByModels();
    }

    @GetMapping("/usage/daily")
    public List<UsageDailySummaryResponse> getUsageDaily() {
        return adminUsageService.getUsageDaily();
    }

    @GetMapping("/usage/by-user/{userId}")
    public List<UsageSummaryResponse> getUsageByUserId(@PathVariable UUID userId) {
        return adminUsageService.getUsageByUserId(userId);
    }

    @GetMapping("/usage/by-organization/{organizationId}")
    public List<UsageSummaryResponse> getUsageByOrganizationId(
            @PathVariable UUID organizationId,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return adminUsageService.getUsageByOrganizationId(organizationId, currentUser);
    }
}
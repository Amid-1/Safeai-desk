package ru.safeai.gateway.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import ru.safeai.gateway.admin.service.AdminUsageService;
import ru.safeai.gateway.chat.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.chat.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminUsageController {

    private final AdminUsageService adminUsageService;

    @GetMapping("/usage/summary")
    public List<UsageSummaryResponse> getUsageSummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateTo,

            @RequestParam(required = false)
            String model,

            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return adminUsageService.getUsageSummary(dateFrom, dateTo, model, currentUser);
    }

    @GetMapping("/usage/users")
    public List<UsageUserSummaryResponse> getUsageByUsers(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateTo,

            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return adminUsageService.getUsageByUsers(
                dateFrom,
                dateTo,
                currentUser
        );
    }

    @GetMapping("/usage/models")
    public List<UsageModelSummaryResponse> getUsageByModels(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateTo,

            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return adminUsageService.getUsageByModels(
                dateFrom,
                dateTo,
                currentUser
        );
    }

    @GetMapping("/usage/daily")
    public List<UsageDailySummaryResponse> getUsageDaily(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateTo,

            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return adminUsageService.getUsageDaily(
                dateFrom,
                dateTo,
                currentUser
        );
    }

    @GetMapping("/usage/by-user/{userId}")
    public List<UsageSummaryResponse> getUsageByUserId(
            @PathVariable UUID userId,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateTo,

            @RequestParam(required = false)
            String model,

            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return adminUsageService.getUsageByUserId(
                userId,
                dateFrom,
                dateTo,
                model,
                currentUser
        );
    }

    @GetMapping("/usage/by-organization/{organizationId}")
    public List<UsageSummaryResponse> getUsageByOrganizationId(
            @PathVariable UUID organizationId,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateFrom,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant dateTo,

            @RequestParam(required = false)
            String model,

            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return adminUsageService.getUsageByOrganizationId(
                organizationId,
                dateFrom,
                dateTo,
                model,
                currentUser
        );
    }
}
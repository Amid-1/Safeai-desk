package ru.safeai.gateway.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.usage.service.UsageQueryService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminUsageController {

    private final UsageQueryService usageQueryService;

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
        return usageQueryService.getUsageSummary(
                dateFrom,
                dateTo,
                model,
                currentUser
        );
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
        return usageQueryService.getUsageByUsers(
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
        return usageQueryService.getUsageByModels(
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
        return usageQueryService.getUsageDaily(
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
        return usageQueryService.getUsageByUserId(
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
        return usageQueryService.getUsageByOrganizationId(
                organizationId,
                dateFrom,
                dateTo,
                model,
                currentUser
        );
    }
}
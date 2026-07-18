package ru.safeai.gateway.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.PagedResponse;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageDateFilter;
import ru.safeai.gateway.usage.dto.UsageDateModelFilter;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsagePageRequest;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.usage.service.UsageQueryService;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminUsageController {

    private final UsageQueryService usageQueryService;

    @GetMapping("/summary")
    public PagedResponse<UsageSummaryResponse> getUsageSummary(
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @Valid @ModelAttribute UsagePageRequest pageRequest,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return PagedResponse.from(
                usageQueryService.getUsageSummary(
                        filter.dateFrom(),
                        filter.dateTo(),
                        filter.normalizedModel(),
                        pageRequest.toPageable(),
                        currentUser
                )
        );
    }

    @GetMapping("/users")
    public PagedResponse<UsageUserSummaryResponse> getUsageByUsers(
            @Valid @ModelAttribute UsageDateFilter filter,
            @Valid @ModelAttribute UsagePageRequest pageRequest,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return PagedResponse.from(
                usageQueryService.getUsageByUsers(
                        filter.dateFrom(),
                        filter.dateTo(),
                        pageRequest.toPageable(),
                        currentUser
                )
        );
    }

    @GetMapping("/models")
    public List<UsageModelSummaryResponse> getUsageByModels(
            @Valid @ModelAttribute UsageDateFilter filter,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return usageQueryService.getUsageByModels(
                filter.dateFrom(),
                filter.dateTo(),
                currentUser
        );
    }

    @GetMapping("/daily")
    public List<UsageDailySummaryResponse> getUsageDaily(
            @Valid @ModelAttribute UsageDateFilter filter,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return usageQueryService.getUsageDaily(
                filter.dateFrom(),
                filter.dateTo(),
                currentUser
        );
    }

    @GetMapping("/users/{userId}")
    public PagedResponse<UsageSummaryResponse> getUsageByUserId(
            @PathVariable UUID userId,
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @Valid @ModelAttribute UsagePageRequest pageRequest,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return PagedResponse.from(
                usageQueryService.getUsageByUserId(
                        userId,
                        filter.dateFrom(),
                        filter.dateTo(),
                        filter.normalizedModel(),
                        pageRequest.toPageable(),
                        currentUser
                )
        );
    }

    @GetMapping("/organizations/{organizationId}")
    public PagedResponse<UsageSummaryResponse> getUsageByOrganizationId(
            @PathVariable UUID organizationId,
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @Valid @ModelAttribute UsagePageRequest pageRequest,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return PagedResponse.from(
                usageQueryService.getUsageByOrganizationId(
                        organizationId,
                        filter.dateFrom(),
                        filter.dateTo(),
                        filter.normalizedModel(),
                        pageRequest.toPageable(),
                        currentUser
                )
        );
    }
}

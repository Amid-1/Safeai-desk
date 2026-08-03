package ru.safeai.gateway.usage.controller;

import jakarta.validation.Valid;
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
import ru.safeai.gateway.usage.dto.UsageDataQualityResponse;
import ru.safeai.gateway.usage.dto.UsageDateFilter;
import ru.safeai.gateway.usage.dto.UsageDateModelFilter;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsagePageRequest;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.usage.service.UsageQueryService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/usage")
@Validated
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UsageController {

    private final UsageQueryService usageQueryService;

    public UsageController(UsageQueryService usageQueryService) {
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/summary")
    public PagedResponse<UsageSummaryResponse> summary(
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
    public PagedResponse<UsageUserSummaryResponse> users(
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
    public List<UsageModelSummaryResponse> models(
            @Valid @ModelAttribute UsageDateFilter filter,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return usageQueryService.getUsageByModels(
                filter.dateFrom(),
                filter.dateTo(),
                currentUser
        );
    }

    /** Daily usage is grouped by UTC calendar date. */
    @GetMapping("/daily")
    public List<UsageDailySummaryResponse> daily(
            @Valid @ModelAttribute UsageDateFilter filter,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return usageQueryService.getUsageDaily(
                filter.dateFrom(),
                filter.dateTo(),
                currentUser
        );
    }

    @GetMapping("/by-user/{userId}")
    public PagedResponse<UsageSummaryResponse> byUser(
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

    @GetMapping("/by-organization/{organizationId}")
    public PagedResponse<UsageSummaryResponse> byOrganization(
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

    @GetMapping("/data-quality")
    public UsageDataQualityResponse dataQuality(
            @Valid @ModelAttribute UsageDateFilter filter,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return usageQueryService.getDataQuality(
                filter.dateFrom(),
                filter.dateTo(),
                currentUser
        );
    }
}

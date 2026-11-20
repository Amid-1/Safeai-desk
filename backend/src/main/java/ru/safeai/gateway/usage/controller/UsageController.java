package ru.safeai.gateway.usage.controller;

import ru.safeai.gateway.common.budget.PlatformBudgetClassification.Status;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.PagedResponse;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageDataQualityResponse;
import ru.safeai.gateway.usage.dto.UsageDateFilter;
import ru.safeai.gateway.usage.dto.UsageDateModelFilter;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageOrganizationSummaryResponse;
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
                filter.userId() != null
                        ? usageQueryService.getUsageSummaryForUser(null, filter.userId(),
                                filter.dateFrom(), filter.dateTo(), filter.normalizedModel(),
                                pageRequest.toPageable(), currentUser)
                        : usageQueryService.getUsageSummary(
                        filter.dateFrom(),
                        filter.dateTo(),
                        filter.normalizedModel(),
                        pageRequest.toPageable(),
                        currentUser
                )
        );
    }

    @GetMapping("/organizations")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PagedResponse<UsageOrganizationSummaryResponse> organizations(
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @Valid @ModelAttribute UsagePageRequest pageRequest,
            @RequestParam(required = false) String organizationQuery,
            @RequestParam(defaultValue = "false") boolean usedOnly,
            @RequestParam(required = false) Status budgetStatus,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        if (budgetStatus != null) {
            return PagedResponse.from(usageQueryService.getUsageByOrganizations(
                    filter.dateFrom(), filter.dateTo(), filter.normalizedModel(), filter.userId(),
                    pageRequest.toPageable(), currentUser, organizationQuery, usedOnly, budgetStatus));
        }
        if (organizationQuery == null && !usedOnly) {
            return PagedResponse.from(usageQueryService.getUsageByOrganizations(
                    filter.dateFrom(), filter.dateTo(), filter.normalizedModel(), filter.userId(),
                    pageRequest.toPageable(), currentUser));
        }
        return PagedResponse.from(usageQueryService.getUsageByOrganizations(
                filter.dateFrom(), filter.dateTo(), filter.normalizedModel(), filter.userId(),
                pageRequest.toPageable(), currentUser, organizationQuery, usedOnly));
    }

    @GetMapping("/users")
    public PagedResponse<UsageUserSummaryResponse> users(
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @Valid @ModelAttribute UsagePageRequest pageRequest,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return PagedResponse.from(
                filter.userId() != null
                        ? usageQueryService.getUsageUsersForUser(null, filter.userId(),
                                filter.dateFrom(), filter.dateTo(), filter.normalizedModel(),
                                pageRequest.toPageable(), currentUser)
                        : usageQueryService.getUsageByUsers(
                        filter.dateFrom(),
                        filter.dateTo(),
                        filter.normalizedModel(),
                        pageRequest.toPageable(),
                        currentUser
                )
        );
    }

    @GetMapping("/models")
    public List<UsageModelSummaryResponse> models(
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        if (filter.userId() != null) {
            return usageQueryService.getUsageModelsForUser(null, filter.userId(),
                    filter.dateFrom(), filter.dateTo(), filter.normalizedModel(), currentUser);
        }
        return usageQueryService.getUsageByModels(
                filter.dateFrom(),
                filter.dateTo(),
                filter.normalizedModel(),
                currentUser
        );
    }

    @GetMapping("/by-organization/{organizationId}/users")
    public PagedResponse<UsageUserSummaryResponse> usersByOrganization(
            @PathVariable UUID organizationId,
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @Valid @ModelAttribute UsagePageRequest pageRequest,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        return PagedResponse.from(
                filter.userId() != null
                        ? usageQueryService.getUsageUsersForUser(organizationId, filter.userId(),
                                filter.dateFrom(), filter.dateTo(), filter.normalizedModel(),
                                pageRequest.toPageable(), currentUser)
                        : usageQueryService.getUsageByUsersForOrganization(
                        organizationId,
                        filter.dateFrom(),
                        filter.dateTo(),
                        filter.normalizedModel(),
                        pageRequest.toPageable(),
                        currentUser
                )
        );
    }

    @GetMapping("/by-organization/{organizationId}/models")
    public List<UsageModelSummaryResponse> modelsByOrganization(
            @PathVariable UUID organizationId,
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        if (filter.userId() != null) {
            return usageQueryService.getUsageModelsForUser(organizationId, filter.userId(),
                    filter.dateFrom(), filter.dateTo(), filter.normalizedModel(), currentUser);
        }
        return usageQueryService.getUsageByModelsForOrganization(
                organizationId,
                filter.dateFrom(),
                filter.dateTo(),
                filter.normalizedModel(),
                currentUser
        );
    }

    /** Daily usage is grouped by UTC calendar date. */
    @GetMapping("/daily")
    public List<UsageDailySummaryResponse> daily(
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        if (filter.userId() != null) {
            return usageQueryService.getUsageDailyForUser(null, filter.userId(),
                    filter.dateFrom(), filter.dateTo(), filter.normalizedModel(), currentUser);
        }
        return usageQueryService.getUsageDaily(
                filter.dateFrom(),
                filter.dateTo(),
                filter.normalizedModel(),
                currentUser
        );
    }

    @GetMapping("/by-organization/{organizationId}/daily")
    public List<UsageDailySummaryResponse> dailyByOrganization(
            @PathVariable UUID organizationId,
            @Valid @ModelAttribute UsageDateModelFilter filter,
            @AuthenticationPrincipal SafeAiUserPrincipal currentUser
    ) {
        if (filter.userId() != null) {
            return usageQueryService.getUsageDailyForUser(organizationId, filter.userId(),
                    filter.dateFrom(), filter.dateTo(), filter.normalizedModel(), currentUser);
        }
        return usageQueryService.getUsageDailyForOrganization(
                organizationId,
                filter.dateFrom(),
                filter.dateTo(),
                filter.normalizedModel(),
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
                filter.userId() != null
                        ? usageQueryService.getUsageSummaryForUser(organizationId, filter.userId(),
                                filter.dateFrom(), filter.dateTo(), filter.normalizedModel(),
                                pageRequest.toPageable(), currentUser)
                        : usageQueryService.getUsageByOrganizationId(
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

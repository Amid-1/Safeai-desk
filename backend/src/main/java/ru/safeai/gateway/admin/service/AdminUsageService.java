package ru.safeai.gateway.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.chat.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.chat.repository.UsageDailySummaryProjection;
import ru.safeai.gateway.chat.repository.UsageQueryRepository;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUsageService {

    private final UsageQueryRepository usageQueryRepository;

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageSummary(
            Instant dateFrom,
            Instant dateTo,
            String model,
            SafeAiUserPrincipal currentUser
    ) {
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        String normalizedModel = normalizeModel(model);

        if (isSuperAdmin(currentUser)) {
            return usageQueryRepository.findUsageSummary(
                    range.from(),
                    range.to(),
                    normalizedModel
            );
        }

        return usageQueryRepository.findUsageByOrganizationId(
                currentUser.getOrganizationId(),
                range.from(),
                range.to(),
                normalizedModel
        );
    }

    @Transactional(readOnly = true)
    public List<UsageUserSummaryResponse> getUsageByUsers(
            Instant dateFrom,
            Instant dateTo,
            SafeAiUserPrincipal currentUser
    ) {
        UsageDateRange range = normalizeRange(dateFrom, dateTo);

        if (isSuperAdmin(currentUser)) {
            return usageQueryRepository.findUsageByUsers(
                    range.from(),
                    range.to()
            );
        }

        return usageQueryRepository.findUsageByUsersByOrganizationId(
                currentUser.getOrganizationId(),
                range.from(),
                range.to()
        );
    }

    @Transactional(readOnly = true)
    public List<UsageModelSummaryResponse> getUsageByModels(
            Instant dateFrom,
            Instant dateTo,
            SafeAiUserPrincipal currentUser
    ) {
        UsageDateRange range = normalizeRange(dateFrom, dateTo);

        if (isSuperAdmin(currentUser)) {
            return usageQueryRepository.findUsageByModels(
                    range.from(),
                    range.to()
            );
        }

        return usageQueryRepository.findUsageByModelsByOrganizationId(
                currentUser.getOrganizationId(),
                range.from(),
                range.to()
        );
    }

    @Transactional(readOnly = true)
    public List<UsageDailySummaryResponse> getUsageDaily(
            Instant dateFrom,
            Instant dateTo,
            SafeAiUserPrincipal currentUser
    ) {
        UsageDateRange range = normalizeRange(dateFrom, dateTo);

        List<UsageDailySummaryProjection> rows = isSuperAdmin(currentUser)
                ? usageQueryRepository.findUsageDaily(
                range.from(),
                range.to()
        )
                : usageQueryRepository.findUsageDailyByOrganizationId(
                currentUser.getOrganizationId(),
                range.from(),
                range.to()
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
            Instant dateFrom,
            Instant dateTo,
            String model,
            SafeAiUserPrincipal currentUser
    ) {
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        String normalizedModel = normalizeModel(model);

        if (isSuperAdmin(currentUser)) {
            return usageQueryRepository.findUsageByUserId(
                    userId,
                    range.from(),
                    range.to(),
                    normalizedModel
            );
        }

        return usageQueryRepository.findUsageByUserIdAndOrganizationId(
                userId,
                currentUser.getOrganizationId(),
                range.from(),
                range.to(),
                normalizedModel
        );
    }

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageByOrganizationId(
            UUID organizationId,
            Instant dateFrom,
            Instant dateTo,
            String model,
            SafeAiUserPrincipal currentUser
    ) {
        if (!isSuperAdmin(currentUser)
                && !currentUser.getOrganizationId().equals(organizationId)) {
            throw new ForbiddenOperationException(
                    "Нельзя смотреть usage другой организации"
            );
        }

        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        String normalizedModel = normalizeModel(model);

        return usageQueryRepository.findUsageByOrganizationId(
                organizationId,
                range.from(),
                range.to(),
                normalizedModel
        );
    }

    private UsageDateRange normalizeRange(Instant dateFrom, Instant dateTo) {
        Instant to = dateTo == null ? Instant.now() : dateTo;
        Instant from = dateFrom == null ? to.minusSeconds(30L * 24 * 60 * 60) : dateFrom;

        if (!from.isBefore(to)) {
            throw new BadRequestException("dateFrom должен быть раньше dateTo");
        }

        return new UsageDateRange(from, to);
    }

    private String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }

        return model.trim();
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal currentUser) {
        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }

    private record UsageDateRange(
            Instant from,
            Instant to
    ) {
    }
}
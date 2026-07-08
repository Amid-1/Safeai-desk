package ru.safeai.gateway.usage.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.usage.repository.UsageDailySummaryProjection;
import ru.safeai.gateway.usage.repository.UsageQueryRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UsageQueryService {

    private static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final UsageQueryRepository usageQueryRepository;

    @Transactional(readOnly = true)
    public List<UsageSummaryResponse> getUsageSummary(
            Instant dateFrom,
            Instant dateTo,
            String model,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

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
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

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
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

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
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

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
        Objects.requireNonNull(userId, "userId не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

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
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

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
        Instant from = dateFrom == null ? to.minus(DEFAULT_RANGE) : dateFrom;

        if (!from.isBefore(to)) {
            throw new BadRequestException("dateFrom должен быть раньше dateTo");
        }

        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BadRequestException("Период usage не должен превышать 366 дней");
        }

        return new UsageDateRange(from, to);
    }

    private String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }

        String normalized = model.trim();

        if (normalized.length() > 100) {
            throw new BadRequestException("model слишком длинный");
        }

        return normalized;
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
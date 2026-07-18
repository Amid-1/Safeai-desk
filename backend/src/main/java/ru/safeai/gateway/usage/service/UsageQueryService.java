package ru.safeai.gateway.usage.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UsageQueryService {

    private static final Duration DEFAULT_RANGE =
            Duration.ofDays(30);

    private static final Duration MAX_RANGE =
            Duration.ofDays(366);

    private final UsageQueryRepository usageQueryRepository;

    @Transactional(readOnly = true)
    public Slice<UsageSummaryResponse> getUsageSummary(
            Instant dateFrom,
            Instant dateTo,
            String model,
            Pageable pageable,
            SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);
        requirePageable(pageable);

        UsageDateRange range =
                normalizeRange(dateFrom, dateTo);

        String normalizedModel =
                normalizeModel(model);

        if (isSuperAdmin(currentUser)) {
            return usageQueryRepository.findUsageSummary(
                    range.from(),
                    range.to(),
                    normalizedModel,
                    pageable
            );
        }

        return usageQueryRepository.findUsageByOrganizationId(
                currentUser.getOrganizationId(),
                range.from(),
                range.to(),
                normalizedModel,
                pageable
        );
    }

    @Transactional(readOnly = true)
    public Slice<UsageUserSummaryResponse> getUsageByUsers(
            Instant dateFrom,
            Instant dateTo,
            Pageable pageable,
            SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);
        requirePageable(pageable);

        UsageDateRange range =
                normalizeRange(dateFrom, dateTo);

        if (isSuperAdmin(currentUser)) {
            return usageQueryRepository.findUsageByUsers(
                    range.from(),
                    range.to(),
                    pageable
            );
        }

        return usageQueryRepository
                .findUsageByUsersByOrganizationId(
                        currentUser.getOrganizationId(),
                        range.from(),
                        range.to(),
                        pageable
                );
    }

    @Transactional(readOnly = true)
    public List<UsageModelSummaryResponse> getUsageByModels(
            Instant dateFrom,
            Instant dateTo,
            SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);

        UsageDateRange range =
                normalizeRange(dateFrom, dateTo);

        if (isSuperAdmin(currentUser)) {
            return usageQueryRepository.findUsageByModels(
                    range.from(),
                    range.to()
            );
        }

        return usageQueryRepository
                .findUsageByModelsByOrganizationId(
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
        requireCurrentUser(currentUser);

        UsageDateRange range =
                normalizeRange(dateFrom, dateTo);

        List<UsageDailySummaryProjection> rows =
                isSuperAdmin(currentUser)
                        ? usageQueryRepository.findUsageDaily(
                        range.from(),
                        range.to()
                )
                        : usageQueryRepository
                        .findUsageDailyByOrganizationId(
                                currentUser.getOrganizationId(),
                                range.from(),
                                range.to()
                        );

        return rows.stream()
                .map(this::toDailyResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Slice<UsageSummaryResponse> getUsageByUserId(
            UUID userId,
            Instant dateFrom,
            Instant dateTo,
            String model,
            Pageable pageable,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        requireCurrentUser(currentUser);
        requirePageable(pageable);

        UsageDateRange range =
                normalizeRange(dateFrom, dateTo);

        String normalizedModel =
                normalizeModel(model);

        if (isSuperAdmin(currentUser)) {
            return usageQueryRepository.findUsageByUserId(
                    userId,
                    range.from(),
                    range.to(),
                    normalizedModel,
                    pageable
            );
        }

        return usageQueryRepository
                .findUsageByUserIdAndOrganizationId(
                        userId,
                        currentUser.getOrganizationId(),
                        range.from(),
                        range.to(),
                        normalizedModel,
                        pageable
                );
    }

    @Transactional(readOnly = true)
    public Slice<UsageSummaryResponse> getUsageByOrganizationId(
            UUID organizationId,
            Instant dateFrom,
            Instant dateTo,
            String model,
            Pageable pageable,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        requireCurrentUser(currentUser);
        requirePageable(pageable);

        if (!isSuperAdmin(currentUser)
                && !currentUser
                .getOrganizationId()
                .equals(organizationId)) {
            throw new ForbiddenOperationException(
                    "Нельзя смотреть usage другой организации"
            );
        }

        UsageDateRange range =
                normalizeRange(dateFrom, dateTo);

        String normalizedModel =
                normalizeModel(model);

        return usageQueryRepository.findUsageByOrganizationId(
                organizationId,
                range.from(),
                range.to(),
                normalizedModel,
                pageable
        );
    }

    private UsageDailySummaryResponse toDailyResponse(
            UsageDailySummaryProjection row
    ) {
        return new UsageDailySummaryResponse(
                row.getUsageDate(),
                row.getInputTokens(),
                row.getOutputTokens(),
                row.getCostUsd()
        );
    }

    private UsageDateRange normalizeRange(
            Instant dateFrom,
            Instant dateTo
    ) {
        Instant to = dateTo == null
                ? Instant.now()
                : dateTo;

        Instant from = dateFrom == null
                ? to.minus(DEFAULT_RANGE)
                : dateFrom;

        if (!from.isBefore(to)) {
            throw new BadRequestException(
                    "dateFrom должен быть раньше dateTo"
            );
        }

        Duration requestedRange =
                Duration.between(from, to);

        if (requestedRange.compareTo(MAX_RANGE) > 0) {
            throw new BadRequestException(
                    "Период usage не должен превышать 366 дней"
            );
        }

        return new UsageDateRange(from, to);
    }

    private String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }

        String normalized = model.trim();

        if (normalized.length() > 100) {
            throw new BadRequestException(
                    "model слишком длинный"
            );
        }

        return normalized;
    }

    private void requireCurrentUser(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
    }

    private void requirePageable(Pageable pageable) {
        Objects.requireNonNull(
                pageable,
                "pageable не должен быть null"
        );
    }

    private boolean isSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
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

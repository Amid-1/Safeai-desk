package ru.safeai.gateway.usage.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageDataQualityResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.usage.repository.UsageInstantRange;
import ru.safeai.gateway.usage.repository.UsageQueryCriteria;
import ru.safeai.gateway.usage.repository.UsageQueryPlan;
import ru.safeai.gateway.usage.repository.UsageQueryRepository;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UsageQueryService {

    private final UsageQueryRepository usageQueryRepository;
    private final UsageRollupStateRepository rollupStateRepository;
    private final UsageProperties properties;
    private final UsageReportExecutor reportExecutor;
    private final Clock clock;

    public UsageQueryService(
            UsageQueryRepository usageQueryRepository,
            UsageRollupStateRepository rollupStateRepository,
            UsageProperties properties,
            UsageReportExecutor reportExecutor,
            Clock clock
    ) {
        this.usageQueryRepository = usageQueryRepository;
        this.rollupStateRepository = rollupStateRepository;
        this.properties = properties;
        this.reportExecutor = reportExecutor;
        this.clock = clock;
    }

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

        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(),
                range.to(),
                isSuperAdmin(currentUser)
                        ? null
                        : currentUser.getOrganizationId(),
                null,
                normalizeModel(model)
        );
        UsageQueryPlan plan = buildPlan(range);

        return reportExecutor.execute(
                "summary",
                () -> usageQueryRepository.findSummary(
                        criteria,
                        plan,
                        pageable
                )
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

        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(),
                range.to(),
                isSuperAdmin(currentUser)
                        ? null
                        : currentUser.getOrganizationId(),
                null,
                null
        );
        UsageQueryPlan plan = buildPlan(range);

        return reportExecutor.execute(
                "users",
                () -> usageQueryRepository.findUsers(
                        criteria,
                        plan,
                        pageable
                )
        );
    }

    @Transactional(readOnly = true)
    public List<UsageModelSummaryResponse> getUsageByModels(
            Instant dateFrom,
            Instant dateTo,
            SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);

        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(),
                range.to(),
                isSuperAdmin(currentUser)
                        ? null
                        : currentUser.getOrganizationId(),
                null,
                null
        );
        UsageQueryPlan plan = buildPlan(range);

        return reportExecutor.execute(
                "models",
                () -> usageQueryRepository.findModels(
                        criteria,
                        plan
                )
        );
    }

    @Transactional(readOnly = true)
    public List<UsageDailySummaryResponse> getUsageDaily(
            Instant dateFrom,
            Instant dateTo,
            SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);

        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(),
                range.to(),
                isSuperAdmin(currentUser)
                        ? null
                        : currentUser.getOrganizationId(),
                null,
                null
        );
        UsageQueryPlan plan = buildPlan(range);

        return reportExecutor.execute(
                "daily",
                () -> usageQueryRepository.findDaily(
                        criteria,
                        plan
                )
        );
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

        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(),
                range.to(),
                isSuperAdmin(currentUser)
                        ? null
                        : currentUser.getOrganizationId(),
                userId,
                normalizeModel(model)
        );
        UsageQueryPlan plan = buildPlan(range);

        return reportExecutor.execute(
                "by-user",
                () -> usageQueryRepository.findSummary(
                        criteria,
                        plan,
                        pageable
                )
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

        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(),
                range.to(),
                organizationId,
                null,
                normalizeModel(model)
        );
        UsageQueryPlan plan = buildPlan(range);

        return reportExecutor.execute(
                "by-organization",
                () -> usageQueryRepository.findSummary(
                        criteria,
                        plan,
                        pageable
                )
        );
    }

    @Transactional(readOnly = true)
    public UsageDataQualityResponse getDataQuality(
            Instant dateFrom,
            Instant dateTo,
            SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);

        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(),
                range.to(),
                isSuperAdmin(currentUser)
                        ? null
                        : currentUser.getOrganizationId(),
                null,
                null
        );
        UsageQueryPlan plan = buildPlan(range);

        return reportExecutor.execute(
                "data-quality",
                () -> usageQueryRepository.findDataQuality(
                        criteria,
                        plan
                )
        );
    }

    private UsageDateRange normalizeRange(
            Instant dateFrom,
            Instant dateTo
    ) {
        Instant to = dateTo == null
                ? clock.instant()
                : dateTo;
        Instant from = dateFrom == null
                ? to.minus(properties.defaultRange())
                : dateFrom;

        if (!from.isBefore(to)) {
            throw new BadRequestException(
                    "dateFrom должен быть раньше dateTo"
            );
        }

        Duration requestedRange = Duration.between(from, to);

        if (requestedRange.compareTo(properties.maxRange()) > 0) {
            throw new BadRequestException(
                    "Период usage не должен превышать "
                            + properties.maxRange().toDays()
                            + " дней"
            );
        }

        return new UsageDateRange(from, to);
    }

    private UsageQueryPlan buildPlan(
            UsageDateRange range
    ) {
        if (!properties.rollup().isEnabled()) {
            return liveOnlyPlan(range);
        }

        LocalDate lastCompletedDate =
                rollupStateRepository.findLastCompletedDate();

        if (lastCompletedDate == null) {
            return liveOnlyPlan(range);
        }

        Instant rollupStartInstant =
                ceilUtcDay(range.from());

        Instant rollupCoverageEnd = lastCompletedDate
                .plusDays(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);

        Instant rollupEndInstant = min(
                floorUtcDay(range.to()),
                rollupCoverageEnd
        );

        if (!rollupStartInstant.isBefore(
                rollupEndInstant
        )) {
            return liveOnlyPlan(range);
        }

        List<UsageInstantRange> liveRanges =
                buildLiveRanges(
                        range,
                        rollupStartInstant,
                        rollupEndInstant
                );

        return validateLiveFallback(
                new UsageQueryPlan(
                        LocalDate.ofInstant(
                                rollupStartInstant,
                                ZoneOffset.UTC
                        ),
                        LocalDate.ofInstant(
                                rollupEndInstant,
                                ZoneOffset.UTC
                        ),
                        liveRanges
                )
        );
    }

    private List<UsageInstantRange> buildLiveRanges(
            UsageDateRange range,
            Instant rollupStartInstant,
            Instant rollupEndInstant
    ) {
        List<UsageInstantRange> liveRanges =
                new ArrayList<>(2);

        if (range.from().isBefore(
                rollupStartInstant
        )) {
            liveRanges.add(
                    new UsageInstantRange(
                            range.from(),
                            rollupStartInstant
                    )
            );
        }

        if (rollupEndInstant.isBefore(
                range.to()
        )) {
            liveRanges.add(
                    new UsageInstantRange(
                            rollupEndInstant,
                            range.to()
                    )
            );
        }

        return liveRanges;
    }

    private UsageQueryPlan liveOnlyPlan(UsageDateRange range) {
        return validateLiveFallback(
                new UsageQueryPlan(
                        null,
                        null,
                        List.of(
                                new UsageInstantRange(
                                        range.from(),
                                        range.to()
                                )
                        )
                )
        );
    }

    private UsageQueryPlan validateLiveFallback(
            UsageQueryPlan plan
    ) {
        Duration liveDuration = Duration.ZERO;

        for (UsageInstantRange range : plan.liveRanges()) {
            liveDuration = liveDuration.plus(
                    Duration.between(
                            range.from(),
                            range.to()
                    )
            );
        }

        if (liveDuration.compareTo(properties.maxLiveRange()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Usage rollup backfill ещё не покрывает запрошенный "
                            + "диапазон. Максимальный live fallback: "
                            + properties.maxLiveRange().toDays()
                            + " дней"
            );
        }

        return plan;
    }

    private Instant ceilUtcDay(Instant instant) {
        Instant floor = floorUtcDay(instant);

        if (floor.equals(instant)) {
            return instant;
        }

        return floor.plus(Duration.ofDays(1));
    }

    private Instant floorUtcDay(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }

    private Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
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

        if (pageable.getPageNumber() < 0) {
            throw new BadRequestException(
                    "page не может быть отрицательным"
            );
        }

        if (pageable.getPageSize() < 1
                || pageable.getPageSize()
                > properties.maxPageSize()) {
            throw new BadRequestException(
                    "size должен находиться в диапазоне 1.."
                            + properties.maxPageSize()
            );
        }

        if (pageable.getSort().isSorted()) {
            throw new BadRequestException(
                    "Произвольная сортировка usage-отчётов запрещена"
            );
        }
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

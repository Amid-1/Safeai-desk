package ru.safeai.gateway.usage.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
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

/** Report transactions are owned by UsageReportExecutor AFTER admission. */
@Service
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class UsageQueryService {

    // Avoid pathological OFFSET scans; never truncate or silently change the page.
    private static final long MAX_REPORT_OFFSET = 50_000L;
    private static final Duration MAX_UNPAGED_DAILY_RANGE = Duration.ofDays(366);

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
        this.usageQueryRepository = Objects.requireNonNull(usageQueryRepository);
        this.rollupStateRepository = Objects.requireNonNull(rollupStateRepository);
        this.properties = Objects.requireNonNull(properties);
        this.reportExecutor = Objects.requireNonNull(reportExecutor);
        this.clock = Objects.requireNonNull(clock);
    }

    public Slice<UsageSummaryResponse> getUsageSummary(
            Instant dateFrom, Instant dateTo, String model,
            Pageable pageable, SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);
        requirePageable(pageable);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(), range.to(), scope(currentUser), null, normalizeModel(model));
        return reportExecutor.execute("summary", () ->
                usageQueryRepository.findSummary(criteria, buildPlan(range), pageable));
    }

    public Slice<UsageUserSummaryResponse> getUsageByUsers(
            Instant dateFrom, Instant dateTo,
            Pageable pageable, SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);
        requirePageable(pageable);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = organizationCriteria(range, scope(currentUser));
        return reportExecutor.execute("users", () ->
                usageQueryRepository.findUsers(criteria, buildPlan(range), pageable));
    }

    public List<UsageModelSummaryResponse> getUsageByModels(
            Instant dateFrom, Instant dateTo, SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = organizationCriteria(range, scope(currentUser));
        return reportExecutor.execute("models", () ->
                usageQueryRepository.findModels(criteria, buildPlan(range)));
    }

    public List<UsageDailySummaryResponse> getUsageDaily(
            Instant dateFrom, Instant dateTo, SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        requireBoundedDailyRange(range);
        UsageQueryCriteria criteria = organizationCriteria(range, scope(currentUser));
        return reportExecutor.execute("daily", () ->
                usageQueryRepository.findDaily(criteria, buildPlan(range)));
    }

    /** Explicit tenant selection must never fall back to global data. */
    public Slice<UsageUserSummaryResponse> getUsageByUsersForOrganization(
            UUID organizationId, Instant dateFrom, Instant dateTo,
            Pageable pageable, SafeAiUserPrincipal currentUser
    ) {
        requireOrganizationAccess(organizationId, currentUser);
        requirePageable(pageable);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        return reportExecutor.execute("organization-users", () ->
                usageQueryRepository.findUsers(
                        organizationCriteria(range, organizationId),
                        buildPlan(range), pageable));
    }

    public List<UsageModelSummaryResponse> getUsageByModelsForOrganization(
            UUID organizationId, Instant dateFrom, Instant dateTo,
            SafeAiUserPrincipal currentUser
    ) {
        requireOrganizationAccess(organizationId, currentUser);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        return reportExecutor.execute("organization-models", () ->
                usageQueryRepository.findModels(
                        organizationCriteria(range, organizationId), buildPlan(range)));
    }

    public List<UsageDailySummaryResponse> getUsageDailyForOrganization(
            UUID organizationId, Instant dateFrom, Instant dateTo,
            SafeAiUserPrincipal currentUser
    ) {
        requireOrganizationAccess(organizationId, currentUser);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        requireBoundedDailyRange(range);
        return reportExecutor.execute("organization-daily", () ->
                usageQueryRepository.findDaily(
                        organizationCriteria(range, organizationId), buildPlan(range)));
    }

    public Slice<UsageSummaryResponse> getUsageByUserId(
            UUID userId, Instant dateFrom, Instant dateTo, String model,
            Pageable pageable, SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(userId, "userId не должен быть null");
        requireCurrentUser(currentUser);
        requirePageable(pageable);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(), range.to(), scope(currentUser), userId, normalizeModel(model));
        return reportExecutor.execute("by-user", () ->
                usageQueryRepository.findSummary(criteria, buildPlan(range), pageable));
    }

    public Slice<UsageSummaryResponse> getUsageByOrganizationId(
            UUID organizationId, Instant dateFrom, Instant dateTo, String model,
            Pageable pageable, SafeAiUserPrincipal currentUser
    ) {
        requireOrganizationAccess(organizationId, currentUser);
        requirePageable(pageable);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = new UsageQueryCriteria(
                range.from(), range.to(), organizationId, null, normalizeModel(model));
        return reportExecutor.execute("by-organization", () ->
                usageQueryRepository.findSummary(criteria, buildPlan(range), pageable));
    }

    public UsageDataQualityResponse getDataQuality(
            Instant dateFrom, Instant dateTo, SafeAiUserPrincipal currentUser
    ) {
        requireCurrentUser(currentUser);
        UsageDateRange range = normalizeRange(dateFrom, dateTo);
        UsageQueryCriteria criteria = organizationCriteria(range, scope(currentUser));
        // BOTH quality queries now execute within one REPEATABLE_READ snapshot.
        return reportExecutor.execute("data-quality", () ->
                usageQueryRepository.findDataQuality(criteria, buildPlan(range)));
    }

    private void requireBoundedDailyRange(UsageDateRange range) {
        if (Duration.between(range.from(), range.to()).compareTo(MAX_UNPAGED_DAILY_RANGE) > 0) {
            throw new BadRequestException("Непагинированный daily usage не должен превышать 366 дней");
        }
    }

    private UsageDateRange normalizeRange(Instant dateFrom, Instant dateTo) {
        Instant to = dateTo == null ? clock.instant() : dateTo;
        Instant from = dateFrom == null ? to.minus(properties.defaultRange()) : dateFrom;
        if (!from.isBefore(to)) {
            throw new BadRequestException("dateFrom должен быть раньше dateTo");
        }
        Duration requestedRange = Duration.between(from, to);
        if (requestedRange.compareTo(properties.maxRange()) > 0) {
            throw new BadRequestException("Период usage не должен превышать "
                    + properties.maxRange().toDays() + " дней");
        }
        return new UsageDateRange(from, to);
    }

    private UsageQueryCriteria organizationCriteria(UsageDateRange range, UUID organizationId) {
        return new UsageQueryCriteria(range.from(), range.to(), organizationId, null, null);
    }

    private UUID scope(SafeAiUserPrincipal user) {
        return isSuperAdmin(user) ? null : user.getOrganizationId();
    }

    private void requireOrganizationAccess(UUID organizationId, SafeAiUserPrincipal currentUser) {
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");
        requireCurrentUser(currentUser);
        if (!isSuperAdmin(currentUser)
                && !currentUser.getOrganizationId().equals(organizationId)) {
            throw new ForbiddenOperationException("Нельзя смотреть usage другой организации");
        }
    }

    private UsageQueryPlan buildPlan(UsageDateRange range) {
        if (!properties.rollup().isEnabled()) {
            return liveOnlyPlan(range);
        }
        LocalDate lastCompletedDate = rollupStateRepository.findLastCompletedDate();
        if (lastCompletedDate == null) {
            return liveOnlyPlan(range);
        }
        Instant rollupStartInstant = ceilUtcDay(range.from());
        Instant rollupCoverageEnd = lastCompletedDate.plusDays(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant rollupEndInstant = min(floorUtcDay(range.to()), rollupCoverageEnd);
        if (!rollupStartInstant.isBefore(rollupEndInstant)) {
            return liveOnlyPlan(range);
        }
        return validateLiveFallback(new UsageQueryPlan(
                LocalDate.ofInstant(rollupStartInstant, ZoneOffset.UTC),
                LocalDate.ofInstant(rollupEndInstant, ZoneOffset.UTC),
                buildLiveRanges(range, rollupStartInstant, rollupEndInstant)
        ));
    }

    private List<UsageInstantRange> buildLiveRanges(
            UsageDateRange range, Instant rollupStartInstant, Instant rollupEndInstant
    ) {
        List<UsageInstantRange> live = new ArrayList<>(2);
        if (range.from().isBefore(rollupStartInstant)) {
            live.add(new UsageInstantRange(range.from(), rollupStartInstant));
        }
        if (rollupEndInstant.isBefore(range.to())) {
            live.add(new UsageInstantRange(rollupEndInstant, range.to()));
        }
        return live;
    }

    private UsageQueryPlan liveOnlyPlan(UsageDateRange range) {
        return validateLiveFallback(new UsageQueryPlan(
                null, null, List.of(new UsageInstantRange(range.from(), range.to()))));
    }

    private UsageQueryPlan validateLiveFallback(UsageQueryPlan plan) {
        Duration liveDuration = Duration.ZERO;
        for (UsageInstantRange interval : plan.liveRanges()) {
            liveDuration = liveDuration.plus(Duration.between(interval.from(), interval.to()));
        }
        if (liveDuration.compareTo(properties.maxLiveRange()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Usage rollup backfill ещё не покрывает запрошенный диапазон. Максимальный live fallback: "
                            + properties.maxLiveRange().toDays() + " дней");
        }
        return plan;
    }

    private Instant ceilUtcDay(Instant instant) {
        Instant floor = floorUtcDay(instant);
        return floor.equals(instant) ? instant : floor.plus(Duration.ofDays(1));
    }

    private Instant floorUtcDay(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
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
            throw new BadRequestException("model слишком длинный");
        }
        return normalized;
    }

    private void requireCurrentUser(SafeAiUserPrincipal currentUser) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
    }

    private void requirePageable(Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable не должен быть null");
        if (pageable.getPageNumber() < 0) {
            throw new BadRequestException("page не может быть отрицательным");
        }
        if (pageable.getPageSize() < 1 || pageable.getPageSize() > properties.maxPageSize()) {
            throw new BadRequestException("size должен находиться в диапазоне 1.."
                    + properties.maxPageSize());
        }
        if (pageable.getSort().isSorted()) {
            throw new BadRequestException("Произвольная сортировка usage-отчётов запрещена");
        }
        if (pageable.getOffset() > MAX_REPORT_OFFSET) {
            throw new BadRequestException(
                    "OFFSET-пагинация usage ограничена " + MAX_REPORT_OFFSET
                            + " строками; сузьте фильтры или диапазон дат");
        }
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }

    private record UsageDateRange(Instant from, Instant to) {
    }
}

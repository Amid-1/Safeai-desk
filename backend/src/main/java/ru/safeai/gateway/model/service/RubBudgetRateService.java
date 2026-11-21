package ru.safeai.gateway.model.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiException;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.domain.RubBudgetSnapshot;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.RubBudgetInput;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Daily per-process single-flight cache. No stale-day fallback or client-selected rate. */
@Service
public class RubBudgetRateService {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private final Clock clock;
    private final Source source;
    private Quote cached;
    private Instant retryAfter = Instant.MIN;

    @FunctionalInterface
    interface Source { CbrUsdRateSource.Rate get(LocalDate day) throws IOException; }

    public record Quote(String rubPerUsd, LocalDate quoteDate, LocalDate effectiveDate,
                        Instant fetchedAt, String source) {}

    @Autowired
    public RubBudgetRateService(Clock clock) {
        this(clock, new CbrUsdRateSource()::fetch);
    }

    RubBudgetRateService(Clock clock, Source source) {
        this.clock = Objects.requireNonNull(clock);
        this.source = Objects.requireNonNull(source);
    }

    public synchronized Quote current() {
        LocalDate day = today();
        if (cached != null && cached.quoteDate().equals(day)) return cached;
        if (clock.instant().isBefore(retryAfter)) throw unavailable();
        try {
            CbrUsdRateSource.Rate fetched = source.get(day);
            Instant at = DatabaseTimestampNormalizer.normalize(clock.instant());
            if (!at.atZone(MOSCOW).toLocalDate().equals(day)) {
                throw new IOException("Rate request crossed midnight");
            }
            cached = new Quote(fetched.rubPerUsd().toPlainString(), day, fetched.effectiveDate(),
                    at, RubBudgetSnapshot.SOURCE);
            return cached;
        } catch (IOException failure) {
            retryAfter = clock.instant().plusSeconds(30);
            throw unavailable();
        }
    }

    RubBudgetSnapshot resolve(CreateOrganizationModelPolicyVersionRequest request,
                              OrganizationModelPolicy previous) {
        RubBudgetInput input = request.rubBudget();
        if (input == null) return null;
        if (request.maxRequestCostUsd() != null || request.monthlyBudgetUsd() != null) {
            throw new BadRequestException("Укажите бюджет только в одной валюте.");
        }
        BigDecimal max = OrganizationModelPolicyRules.normalizeMoney(input.maxRequestCostRub(), "maxRequestCostRub");
        BigDecimal monthly = OrganizationModelPolicyRules.normalizeMoney(input.monthlyBudgetRub(), "monthlyBudgetRub");
        if (max == null && monthly == null) throw new BadRequestException("Рублёвый бюджет не задан.");
        if (input.rubPerUsd() == null || input.rubPerUsd().signum() <= 0
                || input.rubPerUsd().scale() > 8
                || (long) input.rubPerUsd().precision() - input.rubPerUsd().scale() > 10
                || input.quoteDate() == null || input.effectiveDate() == null) {
            throw new BadRequestException("Некорректная котировка бюджета.");
        }
        RubBudgetSnapshot saved = previous == null ? null : previous.rubBudget();
        // Unrelated policy edits must not reprice the existing budget.
        if (saved != null && matches(input, saved.rubPerUsd(), saved.quoteDate(), saved.effectiveDate())
                && equalMoney(max, saved.maxRequestCostRub())
                && equalMoney(monthly, saved.monthlyBudgetRub())) {
            return saved;
        }
        if (!today().equals(input.quoteDate())) throw stale();
        Quote quote = current();
        BigDecimal rate = new BigDecimal(quote.rubPerUsd());
        if (!matches(input, rate, quote.quoteDate(), quote.effectiveDate())) throw stale();
        RubBudgetSnapshot snapshot = new RubBudgetSnapshot(max, monthly, rate,
                quote.quoteDate(), quote.effectiveDate(), quote.fetchedAt());
        OrganizationModelPolicyRules.normalizeMoney(snapshot.maxRequestCostUsd(), "maxRequestCostUsd");
        OrganizationModelPolicyRules.normalizeMoney(snapshot.monthlyBudgetUsd(), "monthlyBudgetUsd");
        return snapshot;
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(MOSCOW)); }

    void requireCurrentIfChanged(RubBudgetSnapshot snapshot, OrganizationModelPolicy previous, Instant now) {
        if (snapshot != null && (previous == null || !Objects.equals(snapshot, previous.rubBudget()))
                && !snapshot.quoteDate().equals(now.atZone(MOSCOW).toLocalDate())) throw stale();
    }

    private static boolean equalMoney(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? a == b : a.compareTo(b) == 0;
    }

    private static boolean matches(RubBudgetInput input, BigDecimal rate, LocalDate date, LocalDate effective) {
        return input.rubPerUsd().compareTo(rate) == 0
                && input.quoteDate().equals(date) && input.effectiveDate().equals(effective);
    }

    private static ConflictException stale() {
        return new ConflictException("Курс изменился или устарел. Получите актуальный курс и проверьте правила снова.");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.BUDGET_RATE_UNAVAILABLE,
                "Курс Банка России временно недоступен. Повторите позже или используйте USD.") {};
    }
}

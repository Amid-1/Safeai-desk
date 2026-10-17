package ru.safeai.gateway.chat.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.chat.config.ChatQuotaProperties;
import ru.safeai.gateway.chat.exception.ChatQuotaExceededException;
import ru.safeai.gateway.chat.quota.ChatQuotaConsumption;
import ru.safeai.gateway.chat.quota.ChatQuotaPolicy;
import ru.safeai.gateway.chat.quota.ChatQuotaReservation;
import ru.safeai.gateway.chat.repository.ChatQuotaRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatQuotaService {
    private final ChatQuotaRepository repository;
    private final ChatQuotaProperties properties;
    private final Clock clock;
    private final ChatDatabaseClockRepository databaseClock;

    /** Old constructor kept for source compatibility of unit tests. */
    public ChatQuotaService(ChatQuotaRepository repository, ChatQuotaProperties properties, Clock clock) {
        this(repository, properties, clock, null);
    }

    @Autowired
    public ChatQuotaService(ChatQuotaRepository repository, ChatQuotaProperties properties,
                            Clock clock, ChatDatabaseClockRepository databaseClock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.databaseClock = databaseClock;
    }

    /** Compatibility only; production passes a route-bound reservation. */
    @Deprecated
    public void reserve(UUID chatId, UUID turnId, UUID clientRequestId,
                        UUID organizationId, UUID userId) {
        if (!properties.isEnabled()) return;
        Instant now = now();
        LocalDate period = period(now);
        ChatQuotaPolicy organizationPolicy = repository.lockOrganizationPolicy(organizationId);
        ChatQuotaPolicy userPolicy = repository.lockUserPolicy(userId);
        validateLegacy("organization", chatId, turnId, clientRequestId,
                organizationPolicy, repository.organizationConsumption(organizationId, period));
        validateLegacy("user", chatId, turnId, clientRequestId,
                userPolicy, repository.userConsumption(userId, period));
        repository.insertReservation(turnId, organizationId, userId, period, properties, now);
    }

    /** Uses the selected immutable route instead of deployment-wide static estimates. */
    public void reserve(UUID chatId, UUID turnId, UUID clientRequestId,
                        UUID organizationId, UUID userId, ChatQuotaReservation reservation) {
        if (!properties.isEnabled()) return;
        Objects.requireNonNull(reservation, "reservation");
        boolean schemeVerified = reservation.costVerifiable()
                && repository.hasVerifiedRoutePricingScheme(reservation.modelRouteDecisionId());
        ChatQuotaReservation bound = schemeVerified ? reservation : new ChatQuotaReservation(
                reservation.modelRouteDecisionId(), reservation.inputUnits(),
                reservation.outputUnits(), null, false);
        Instant now = now();
        LocalDate period = period(now);
        // Identical lock order across all callers prevents policy-row deadlocks.
        ChatQuotaPolicy organizationPolicy = repository.lockOrganizationPolicy(organizationId);
        ChatQuotaPolicy userPolicy = repository.lockUserPolicy(userId);
        validateRoute("organization", chatId, turnId, clientRequestId,
                organizationPolicy, repository.organizationConsumption(organizationId, period), bound);
        validateRoute("user", chatId, turnId, clientRequestId,
                userPolicy, repository.userConsumption(userId, period), bound);
        repository.insertReservation(turnId, organizationId, userId, period, bound, now);
    }

    public void settleSuccess(UUID turnId, AiChatResponse response, Instant now) {
        if (properties.isEnabled()) repository.settleSuccess(turnId, response, now);
    }

    public void releaseFailure(UUID turnId, Instant now) {
        if (properties.isEnabled()) repository.releaseFailure(turnId, now);
    }

    public void markAmbiguous(UUID turnId, Instant now) {
        if (properties.isEnabled()) repository.markAmbiguous(turnId, now);
    }

    private void validateRoute(String scope, UUID chatId, UUID turnId, UUID clientRequestId,
                               ChatQuotaPolicy policy, ChatQuotaConsumption consumed,
                               ChatQuotaReservation reserved) {
        if (!policy.enabled()) return;
        if (consumed.usageUnverifiable()
                && (policy.monthlyInputTokenLimit() != null
                || policy.monthlyOutputTokenLimit() != null)) {
            deny(scope + ".monthlyTokensUnverifiable", chatId, turnId, clientRequestId);
        }
        if (policy.monthlyCostLimitUsd() != null
                && (consumed.costUnverifiable() || !reserved.costVerifiable())) {
            deny(scope + ".monthlyCostUnverifiable", chatId, turnId, clientRequestId);
        }
        exceeds(policy.monthlyRequestLimit(), consumed.requests(), 1,
                scope + ".monthlyRequests", chatId, turnId, clientRequestId);
        exceeds(policy.monthlyInputTokenLimit(), consumed.inputTokens(), reserved.inputUnits(),
                scope + ".monthlyInputTokens", chatId, turnId, clientRequestId);
        exceeds(policy.monthlyOutputTokenLimit(), consumed.outputTokens(), reserved.outputUnits(),
                scope + ".monthlyOutputTokens", chatId, turnId, clientRequestId);
        if (policy.monthlyCostLimitUsd() != null && consumed.costUsd()
                .add(reserved.maxCostUsd()).compareTo(policy.monthlyCostLimitUsd()) > 0) {
            deny(scope + ".monthlyCostUsd", chatId, turnId, clientRequestId);
        }
    }

    private void validateLegacy(String scope, UUID chatId, UUID turnId, UUID clientRequestId,
                                ChatQuotaPolicy policy, ChatQuotaConsumption consumed) {
        if (!policy.enabled()) return;
        exceeds(policy.monthlyRequestLimit(), consumed.requests(), 1,
                scope + ".monthlyRequests", chatId, turnId, clientRequestId);
        exceeds(policy.monthlyInputTokenLimit(), consumed.inputTokens(),
                properties.reservationInputTokens(), scope + ".monthlyInputTokens",
                chatId, turnId, clientRequestId);
        exceeds(policy.monthlyOutputTokenLimit(), consumed.outputTokens(),
                properties.reservationOutputTokens(), scope + ".monthlyOutputTokens",
                chatId, turnId, clientRequestId);
        if (policy.monthlyCostLimitUsd() != null && consumed.costUsd()
                .add(properties.reservationCostUsd()).compareTo(policy.monthlyCostLimitUsd()) > 0) {
            deny(scope + ".monthlyCostUsd", chatId, turnId, clientRequestId);
        }
    }

    private static void exceeds(Long limit, long used, long add, String dimension,
                                UUID chatId, UUID turnId, UUID clientRequestId) {
        if (limit != null && (used > limit || add > limit - used)) {
            deny(dimension, chatId, turnId, clientRequestId);
        }
    }

    private static void deny(String dimension, UUID chatId, UUID turnId, UUID clientRequestId) {
        throw new ChatQuotaExceededException(chatId, turnId, clientRequestId, dimension);
    }

    private LocalDate period(Instant instant) {
        return YearMonth.from(instant.atZone(properties.effectivePeriodZone())).atDay(1);
    }

    private Instant now() {
        return databaseClock == null ? clock.instant() : databaseClock.now();
    }
}

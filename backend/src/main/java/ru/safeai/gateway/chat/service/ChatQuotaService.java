package ru.safeai.gateway.chat.service;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.chat.config.ChatQuotaProperties;
import ru.safeai.gateway.chat.exception.ChatQuotaExceededException;
import ru.safeai.gateway.chat.quota.ChatQuotaConsumption;
import ru.safeai.gateway.chat.quota.ChatQuotaPolicy;
import ru.safeai.gateway.chat.repository.ChatQuotaRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

@Service
public class ChatQuotaService {

    private final ChatQuotaRepository repository;
    private final ChatQuotaProperties properties;
    private final Clock clock;

    public ChatQuotaService(
            ChatQuotaRepository repository,
            ChatQuotaProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public void reserve(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            UUID organizationId,
            UUID userId
    ) {
        if (!properties.isEnabled()) {
            return;
        }

        Instant now = clock.instant();
        LocalDate periodStart = YearMonth.from(
                now.atZone(properties.effectivePeriodZone())
        ).atDay(1);

        // Deterministic lock order prevents user/org quota deadlocks.
        ChatQuotaPolicy organizationPolicy =
                repository.lockOrganizationPolicy(organizationId);
        ChatQuotaPolicy userPolicy = repository.lockUserPolicy(userId);

        ChatQuotaConsumption organizationConsumption =
                repository.organizationConsumption(
                        organizationId,
                        periodStart
                );
        ChatQuotaConsumption userConsumption =
                repository.userConsumption(userId, periodStart);

        validate(
                "organization",
                chatId,
                turnId,
                clientRequestId,
                organizationPolicy,
                organizationConsumption
        );
        validate(
                "user",
                chatId,
                turnId,
                clientRequestId,
                userPolicy,
                userConsumption
        );

        repository.insertReservation(
                turnId,
                organizationId,
                userId,
                periodStart,
                properties,
                now
        );
    }

    public void settleSuccess(UUID turnId, AiChatResponse response, Instant now) {
        if (properties.isEnabled()) {
            repository.settleSuccess(turnId, response, now);
        }
    }

    public void releaseFailure(UUID turnId, Instant now) {
        if (properties.isEnabled()) {
            repository.releaseFailure(turnId, now);
        }
    }

    public void markAmbiguous(UUID turnId, Instant now) {
        if (properties.isEnabled()) {
            repository.markAmbiguous(turnId, now);
        }
    }

    private void validate(
            String scope,
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            ChatQuotaPolicy policy,
            ChatQuotaConsumption consumption
    ) {
        if (!policy.enabled()) {
            return;
        }
        exceeds(
                policy.monthlyRequestLimit(),
                consumption.requests(),
                1,
                scope + ".monthlyRequests",
                chatId,
                turnId,
                clientRequestId
        );
        exceeds(
                policy.monthlyInputTokenLimit(),
                consumption.inputTokens(),
                properties.reservationInputTokens(),
                scope + ".monthlyInputTokens",
                chatId,
                turnId,
                clientRequestId
        );
        exceeds(
                policy.monthlyOutputTokenLimit(),
                consumption.outputTokens(),
                properties.reservationOutputTokens(),
                scope + ".monthlyOutputTokens",
                chatId,
                turnId,
                clientRequestId
        );
        if (policy.monthlyCostLimitUsd() != null) {
            BigDecimal projected = consumption.costUsd().add(
                    properties.reservationCostUsd()
            );
            if (projected.compareTo(policy.monthlyCostLimitUsd()) > 0) {
                throw new ChatQuotaExceededException(
                        chatId,
                        turnId,
                        clientRequestId,
                        scope + ".monthlyCostUsd"
                );
            }
        }
    }

    private void exceeds(
            Long limit,
            long consumed,
            long reservation,
            String dimension,
            UUID chatId,
            UUID turnId,
            UUID clientRequestId
    ) {
        if (limit != null && Math.addExact(consumed, reservation) > limit) {
            throw new ChatQuotaExceededException(
                    chatId,
                    turnId,
                    clientRequestId,
                    dimension
            );
        }
    }
}

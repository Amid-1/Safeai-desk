package ru.safeai.gateway.ai.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "model_execution_attempts")
public class ModelExecutionAttemptEntity {
    private static final int MAX_FAILURE_TYPE_LENGTH = 64;

    @Id private UUID id;
    @Column(name = "execution_plan_id", nullable = false) private UUID executionPlanId;
    @Column(name = "attempt_number", nullable = false) private int attemptNumber;
    @Column(name = "provider_attempt_id", nullable = false, unique = true) private UUID providerAttemptId;
    @Column(name = "provider_type", nullable = false, length = 32) private String providerType;
    @Column(name = "provider_configuration_ref", nullable = false, length = 255) private String providerConfigurationRef;
    @Column(name = "provider_configuration_version", nullable = false, length = 64) private String providerConfigurationVersion;
    @Column(name = "deployment_ref", nullable = false, length = 255) private String deploymentRef;
    @Column(name = "deployment_version", nullable = false, length = 64) private String deploymentVersion;
    @Column(name = "requested_physical_model", nullable = false, length = 100) private String requestedPhysicalModel;
    @Column(name = "resolved_physical_model", length = 100) private String resolvedPhysicalModel;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private ExecutionAttemptOutcome outcome;
    @Enumerated(EnumType.STRING) @Column(name = "outcome_certainty", nullable = false, length = 32) private OutcomeCertainty outcomeCertainty;
    @Enumerated(EnumType.STRING) @Column(name = "retry_safety", nullable = false, length = 40) private RetrySafety retrySafety;
    @Enumerated(EnumType.STRING) @Column(name = "fallback_safety", nullable = false, length = 32) private FallbackSafety fallbackSafety;
    @Column(name = "provider_request_id", length = 255) private String providerRequestId;
    @Column(name = "provider_message_id", length = 255) private String providerMessageId;
    @Column(name = "failure_type", length = 64) private String failureType;
    @Enumerated(EnumType.STRING) @Column(name = "usage_status", length = 32) private UsageStatus usageStatus;
    @Column(name = "input_tokens") private Integer inputTokens;
    @Column(name = "cached_input_tokens") private Integer cachedInputTokens;
    @Column(name = "cache_write_input_tokens") private Integer cacheWriteInputTokens;
    @Column(name = "output_tokens") private Integer outputTokens;
    @Column(name = "specialized_dimensions_present") private Boolean specializedDimensionsPresent;
    @Column(name = "specialized_dimensions_valid") private Boolean specializedDimensionsValid;
    @Enumerated(EnumType.STRING) @Column(name = "pricing_status", length = 32) private PricingStatus pricingStatus;
    @Column(name = "cost_usd", precision = 30, scale = 12) private BigDecimal costUsd;
    @Column(length = 3) private String currency;
    @Column(name = "price_book_version", length = 64) private String priceBookVersion;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected ModelExecutionAttemptEntity() { }

    public static ModelExecutionAttemptEntity started(UUID planId, UUID providerAttemptId, int number,
                                                       ProviderExecutionTarget target, Instant now) {
        if (number < 1) {
            throw new IllegalArgumentException(
                    "number должен быть положительным"
            );
        }

        ModelExecutionAttemptEntity value = new ModelExecutionAttemptEntity();
        value.id = UUID.randomUUID();
        value.executionPlanId = Objects.requireNonNull(
                planId,
                "planId не должен быть null"
        );
        value.providerAttemptId = Objects.requireNonNull(
                providerAttemptId,
                "providerAttemptId не должен быть null"
        );
        value.attemptNumber = number;
        target = Objects.requireNonNull(
                target,
                "target не должен быть null"
        );
        now = Objects.requireNonNull(
                now,
                "now не должен быть null"
        );
        value.providerType = target.providerType();
        value.providerConfigurationRef = target.providerConfigurationRef(); value.providerConfigurationVersion = target.providerConfigurationVersion();
        value.deploymentRef = target.deploymentRef(); value.deploymentVersion = target.deploymentVersion();
        value.requestedPhysicalModel = target.requestedPhysicalModel(); value.startedAt = now; value.createdAt = now;
        value.outcome = ExecutionAttemptOutcome.STARTED; value.outcomeCertainty = OutcomeCertainty.AMBIGUOUS;
        value.retrySafety = RetrySafety.SAME_TARGET_RETRY_FORBIDDEN; value.fallbackSafety = FallbackSafety.FALLBACK_FORBIDDEN;
        return value;
    }

    public void succeeded(AiChatResponse response, Instant now) {
        requireStarted();
        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );
        now = requireFinishTime(now);
        ProviderUsageEvidence usage = ProviderUsageEvidence.from(response);
        ProviderPricingEvidence pricing = ProviderPricingEvidence.from(response);
        outcome = ExecutionAttemptOutcome.SUCCEEDED; outcomeCertainty = OutcomeCertainty.KNOWN_EXECUTED;
        retrySafety = RetrySafety.SAME_TARGET_RETRY_FORBIDDEN; fallbackSafety = FallbackSafety.FALLBACK_FORBIDDEN;
        resolvedPhysicalModel = response.model(); providerRequestId = response.providerRequestId(); providerMessageId = response.providerMessageId();
        usageStatus = usage.usageStatus(); inputTokens = usage.inputTokens(); cachedInputTokens = usage.cachedInputTokens();
        cacheWriteInputTokens = usage.cacheWriteInputTokens(); outputTokens = usage.outputTokens();
        specializedDimensionsPresent = usage.specializedDimensionsPresent(); specializedDimensionsValid = usage.specializedDimensionsValid();
        pricingStatus = pricing.pricingStatus(); costUsd = pricing.costUsd(); currency = pricing.currency(); priceBookVersion = pricing.priceBookVersion(); finishedAt = now;
    }

    public void failed(AiProviderException exception, Instant now) {
        requireStarted();
        Objects.requireNonNull(
                exception,
                "exception не должен быть null"
        );
        now = requireFinishTime(now);
        boolean outcomeAmbiguous = exception.isOutcomeAmbiguous();
        outcome = outcomeAmbiguous ? ExecutionAttemptOutcome.AMBIGUOUS : ExecutionAttemptOutcome.FAILED;
        outcomeCertainty = outcomeCertainty(exception);
        retrySafety = !outcomeAmbiguous && exception.isRetryable()
                ? RetrySafety.SAME_TARGET_RETRY_ALLOWED
                : RetrySafety.SAME_TARGET_RETRY_FORBIDDEN;
        fallbackSafety = FallbackSafety.FALLBACK_FORBIDDEN;
        providerRequestId = exception.getProviderRequestId(); failureType = exception.getErrorType().name(); finishedAt = now;
    }

    public void ambiguous(String failure, Instant now) {
        requireStarted();
        now = requireFinishTime(now);
        outcome = ExecutionAttemptOutcome.AMBIGUOUS; outcomeCertainty = OutcomeCertainty.AMBIGUOUS;
        retrySafety = RetrySafety.SAME_TARGET_RETRY_FORBIDDEN; fallbackSafety = FallbackSafety.FALLBACK_FORBIDDEN;
        failureType = requireFailureType(failure); finishedAt = now;
    }

    private OutcomeCertainty outcomeCertainty(
            AiProviderException exception
    ) {
        if (exception.isOutcomeAmbiguous()) {
            return OutcomeCertainty.AMBIGUOUS;
        }

        return exception.getErrorType()
                == ru.safeai.gateway.ai.exception.AiProviderErrorType.CONNECT_FAILURE
                ? OutcomeCertainty.KNOWN_NOT_EXECUTED
                : OutcomeCertainty.KNOWN_REJECTED;
    }

    private Instant requireFinishTime(Instant now) {
        Instant normalized = Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        if (normalized.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "finishedAt не может быть раньше startedAt"
            );
        }

        return normalized;
    }

    private String requireFailureType(String failure) {
        if (failure == null || failure.isBlank()) {
            throw new IllegalArgumentException(
                    "failure не должен быть пустым"
            );
        }

        String normalized = failure.trim();

        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "failure содержит управляющие символы"
            );
        }

        return normalized.length() <= MAX_FAILURE_TYPE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_FAILURE_TYPE_LENGTH);
    }

    private void requireStarted() {
        if (outcome != ExecutionAttemptOutcome.STARTED) {
            throw new IllegalStateException(
                    "Execution attempt is already terminal"
            );
        }
    }
}

package ru.safeai.gateway.ai.pricing;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.execution.ModelExecutionPlanEntity;
import ru.safeai.gateway.ai.execution.ModelExecutionPlanRepository;
import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact immutable catalogue-price settlement for a physical provider call.
 * Never reads application.yml tariffs for a governed operation, and never
 * silently prices an unexpected resolved physical model as the routed model.
 * Provider-specific usage counter semantics are explicit and fail closed.
 */
@Primary
@Service
public final class VersionedCatalogPricingResolver implements PricingResolver {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int INTERNAL_SCALE = 18;
    private static final int MONEY_SCALE = 12;
    private final ModelExecutionPlanRepository plans;
    private final ModelRouteDecisionRepository decisions;
    private final ModelCatalogRepository catalog;
    private final Clock clock;

    public VersionedCatalogPricingResolver(
            ModelExecutionPlanRepository plans,
            ModelRouteDecisionRepository decisions,
            ModelCatalogRepository catalog,
            Clock clock) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Identity-less pricing cannot claim to be catalog-grounded. */
    @Override
    public PricingResult resolve(
            ProviderExecutionTarget target, String resolvedModel, AiTokenUsage usage) {
        return PricingResult.unpriced(clock.instant());
    }

    @Override
    public PricingResult resolve(
            UUID operationId,
            ProviderExecutionTarget target,
            String resolvedModel,
            AiTokenUsage usage) {
        Instant now = clock.instant();
        if (operationId == null || target == null || resolvedModel == null
                || usage == null || usage.usageStatus() != UsageStatus.AVAILABLE
                || !usage.specializedBillingDimensionsValid()) {
            return PricingResult.unpriced(now);
        }
        ModelExecutionPlanEntity plan = plans.findByProviderOperationId(operationId)
                .orElse(null);
        if (plan == null || plan.getModelRouteDecisionId() == null
                || !target.requestedPhysicalModel().equals(resolvedModel)
                || !target.requestedPhysicalModel().equals(plan.getRequestedModel())) {
            return PricingResult.unpriced(now);
        }
        ModelRouteDecision decision = decisions.findById(plan.getModelRouteDecisionId())
                .orElse(null);
        if (decision == null || decision.outcome() != ModelRouteOutcome.ALLOWED
                || decision.decisionIntegrityVersion() != 3
                || decision.selectedCatalogEntryId() == null
                || !plan.getChatTurnId().equals(decision.chatTurnId())
                || !plan.getOrganizationId().equals(decision.organizationId())
                || !target.providerType().equals(decision.selectedProvider())
                || !resolvedModel.equals(decision.selectedProviderModelId())) {
            return PricingResult.unpriced(now);
        }
        ModelCatalogEntry price = catalog.findById(decision.selectedCatalogEntryId())
                .orElse(null);
        if (price == null || decision.selectedCatalogVersion() == null
                || price.version() != decision.selectedCatalogVersion()
                || !price.modelKey().equals(decision.selectedModelKey())
                || !price.provider().equals(target.providerType())
                || !price.providerModelId().equals(resolvedModel)
                || !price.pricingComplete()
                || price.pricingVersion() == null
                || price.pricingVersion().isBlank()
                || (price.pricingStatus() != ModelPricingStatus.CONFIGURED
                    && price.pricingStatus() != ModelPricingStatus.FREE)) {
            return PricingResult.unpriced(now);
        }
        try {
            return calculate(price, target.providerType(), usage, now);
        } catch (ArithmeticException | IllegalArgumentException error) {
            return PricingResult.calculationFailed(now);
        }
    }

    private static PricingResult calculate(
            ModelCatalogEntry price, String provider,
            AiTokenUsage usage, Instant now) {
        Integer input = usage.inputTokens();
        Integer output = usage.outputTokens();
        if (input == null || output == null || input < 0 || output < 0
                || price.inputUsdPer1mTokens() == null
                || price.outputUsdPer1mTokens() == null) {
            return PricingResult.unpriced(now);
        }
        BigDecimal inputRate = price.inputUsdPer1mTokens();
        BigDecimal outputRate = price.outputUsdPer1mTokens();
        Integer cacheRead = usage.cachedInputTokens();
        Integer cacheWrite = usage.cacheWriteInputTokens();
        int read = cacheRead == null ? 0 : cacheRead;
        int write = cacheWrite == null ? 0 : cacheWrite;
        if (read < 0 || write < 0) {
            return PricingResult.calculationFailed(now);
        }
        if (read > 0 && price.cachedInputUsdPer1mTokens() == null
                || write > 0 && price.cacheWriteInputUsdPer1mTokens() == null) {
            return PricingResult.unpriced(now);
        }
        if (!"openai".equals(provider) && !"anthropic".equals(provider)) {
            return PricingResult.unpriced(now);
        }
        long ordinaryInput;
        if ("openai".equals(provider)) {
            // OpenAI Responses usage.input_tokens INCLUDES cached tokens;
            // counted specialized dimensions must be disjoint subsets.
            ordinaryInput = (long) input - read - write;
            if (ordinaryInput < 0) return PricingResult.unpriced(now);
        } else {
            // Anthropic usage.input_tokens EXCLUDES cache_creation and
            // cache_read counters; they are separate input dimensions.
            ordinaryInput = input;
        }
        BigDecimal total = charge(ordinaryInput, inputRate)
                .add(charge(read, read > 0 ? price.cachedInputUsdPer1mTokens() : BigDecimal.ZERO))
                .add(charge(write, write > 0 ? price.cacheWriteInputUsdPer1mTokens() : BigDecimal.ZERO))
                .add(charge(output, outputRate))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (total.precision() - total.scale() > 18) {
            return PricingResult.calculationFailed(now);
        }
        if (price.pricingStatus() == ModelPricingStatus.FREE) {
            if (total.signum() != 0) return PricingResult.calculationFailed(now);
        } else if (total.signum() <= 0) {
            return PricingResult.calculationFailed(now);
        }
        return new PricingResult(
                price.pricingStatus() == ModelPricingStatus.FREE
                        ? PricingStatus.FREE : PricingStatus.PRICED,
                total, "USD", version(price), now);
    }

    private static BigDecimal charge(long units, BigDecimal rate) {
        return BigDecimal.valueOf(units).multiply(rate)
                .divide(MILLION, INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    /** Compact immutable price-book identity, not the mutable static YAML version. */
    private static String version(ModelCatalogEntry price) {
        String value = price.id() + ":" + price.modelKey() + ":"
                + price.version() + ":" + price.pricingVersion() + ":"
                + price.inputUsdPer1mTokens() + ":"
                + price.cachedInputUsdPer1mTokens() + ":"
                + price.cacheWriteInputUsdPer1mTokens() + ":"
                + price.outputUsdPer1mTokens();
        try {
            String sha = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
            return "cat-v1-" + sha.substring(0, 56);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}

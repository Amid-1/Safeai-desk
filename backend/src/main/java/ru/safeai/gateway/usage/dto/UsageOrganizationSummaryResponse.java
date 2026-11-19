package ru.safeai.gateway.usage.dto;

import java.util.Objects;
import java.util.UUID;

/** A current directory entry, aggregated by the tenant recorded on usage facts. */
public record UsageOrganizationSummaryResponse(
        UUID organizationId,
        String organizationName,
        UsageResponseSummary responses,
        UsageTokenSummary usage,
        Cost cost
) {
    public UsageOrganizationSummaryResponse {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(organizationName);
        Objects.requireNonNull(responses);
        Objects.requireNonNull(usage);
        Objects.requireNonNull(cost);
    }

    public UsageOrganizationSummaryResponse(UUID organizationId, String organizationName,
            UsageResponseSummary responses, UsageTokenSummary usage, UsageCostSummary cost) {
        this(organizationId, organizationName, responses, usage, Cost.from(cost));
        UsageSummaryInvariants.validate(responses, usage, cost);
    }

    /** Exact decimal, null when not a single cost was established. */
    public record Cost(String knownCostUsd, String currency, long pricedMessages, long freeMessages,
            long unpricedMessages, long pricingFailedMessages, long pricingNotApplicableMessages,
            boolean pricingComplete) {
        private static Cost from(UsageCostSummary cost) {
            boolean known = cost.pricedMessages() > 0 || cost.freeMessages() > 0
                    || (cost.pricingComplete() && cost.pricingNotApplicableMessages() > 0);
            return new Cost(known ? cost.knownCostUsd().toPlainString() : null, cost.currency(),
                    cost.pricedMessages(), cost.freeMessages(), cost.unpricedMessages(),
                    cost.pricingFailedMessages(), cost.pricingNotApplicableMessages(), cost.pricingComplete());
        }
    }
}

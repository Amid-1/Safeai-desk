// frontend/src/features/usage/api/usage/usageTypes.ts
// ============================================================
// frontend/src/features/usage/api/usage/usageTypes.ts
// ============================================================



export type UsageDateRangeFilter = {
    dateFrom?: string
    dateTo?: string
}

export type UsageSummaryFilter =
    UsageDateRangeFilter & {
        model?: string
    }

export type UsageCoverage = {
    assistantMessages: string | null
    completedResponses: string | null
    refusedResponses: string | null
    incompleteResponses: string | null
    failedMessages: string | null
    unclassifiedResponses: string | null

    availableUsageMessages: string | null
    partialUsageMessages: string | null
    missingUsageMessages: string | null
    usageNotApplicableMessages: string | null

    pricedMessages: string | null
    freeMessages: string | null
    unpricedMessages: string | null
    pricingFailedMessages: string | null
    pricingNotApplicableMessages: string | null

    usageComplete: boolean | null
    pricingComplete: boolean | null
}

export type UsageAmounts = {
    /** Только подтверждённые токены. */
    inputTokens: string
    outputTokens: string
    totalTokens: string

    /** Известная часть неполного учёта хранится отдельно от подтверждённых токенов. */
    partialInputTokens: string
    partialOutputTokens: string
    partialTotalTokens: string

    /** Только известная стоимость; null не означает нулевую или бесплатную стоимость. */
    costUsd: string | null
    currency: string

    coverage: UsageCoverage
}

export type UsageSummary = UsageAmounts & {
    userId: string
    userEmail: string
    model: string
}

export type UsageUserSummary = UsageAmounts & {
    userId: string
    userEmail: string
}

export type UsageModelSummary = UsageAmounts & {
    model: string
}

export type UsageDailySummary = UsageAmounts & {
    usageDate: string
    aggregationZone: string
}

export type RequestOptions = {
    signal?: AbortSignal
}

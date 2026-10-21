// ============================================================
// frontend/src/api/adminUsage/adminUsageTypes.ts
// ============================================================



export type UsageFilter = {
    dateFrom?: string
    dateTo?: string
    model?: string
}

export type UsageDateRangeFilter = Pick<
    UsageFilter,
    'dateFrom' | 'dateTo'
>

export type UsageCoverage = {
    assistantMessages: string | null

    availableUsageMessages: string | null
    partialUsageMessages: string | null
    missingUsageMessages: string | null
    usageNotApplicableMessages: string | null

    pricedMessages: string | null
    freeMessages: string | null
    unpricedMessages: string | null
    pricingFailedMessages: string | null
    pricingNotApplicableMessages: string | null

    ambiguousProviderOperations: string | null

    usageComplete: boolean | null
    pricingComplete: boolean | null
}

export type UsageSummary = {
    userId: string
    userEmail: string
    model: string

    inputTokens: string
    outputTokens: string
    totalTokens: string

    /**
     * Известная рассчитанная стоимость.
     * null не означает FREE.
     */
    costUsd: string | null

    coverage: UsageCoverage
}

export type UsageUserSummary = {
    userId: string
    userEmail: string

    inputTokens: string
    outputTokens: string
    totalTokens: string
    costUsd: string | null

    coverage: UsageCoverage
}

export type UsageModelSummary = {
    model: string

    inputTokens: string
    outputTokens: string
    totalTokens: string
    costUsd: string | null

    coverage: UsageCoverage
}

export type UsageDailySummary = {
    usageDate: string

    inputTokens: string
    outputTokens: string
    totalTokens: string
    costUsd: string | null

    coverage: UsageCoverage
}

export type RequestOptions = {
    signal?: AbortSignal
}

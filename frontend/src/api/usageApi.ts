/* frontend/src/api/usageApi.ts */
import {
    API_TIMEOUTS,
    apiRequest,
} from './http'
import {
    buildQueryString,
    normalizePage,
    normalizePageSize,
    uuidPathSegment,
} from './query'
import {
    contractError,
    expectRecord,
    expectString,
    expectUuid,
    parsePageResponse,
} from './runtime'
import type { PageResponse } from '../utils/page'

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
    /** Confirmed tokens only. */
    inputTokens: string
    outputTokens: string
    totalTokens: string

    /** Known part of PARTIAL usage, kept separate from confirmed totals. */
    partialInputTokens: string
    partialOutputTokens: string
    partialTotalTokens: string

    /** Known priced cost only; null means no reliable known value. */
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

type RequestOptions = {
    signal?: AbortSignal
}

export function getUsageSummary(
    page = 0,
    size = 50,
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        '/api/admin/usage/summary',
        page,
        size,
        filter,
        parseUsageSummary,
        options,
    )
}

export function getUsageByUsers(
    page = 0,
    size = 50,
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageUserSummary>> {
    return getUsagePage(
        '/api/admin/usage/users',
        page,
        size,
        filter,
        parseUsageUserSummary,
        options,
    )
}


export function getUsageByOrganization(
    organizationId: string,
    page = 0,
    size = 50,
    filter: UsageSummaryFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        `/api/admin/usage/organizations/${uuidPathSegment(organizationId)}`,
        page,
        size,
        filter,
        parseUsageSummary,
        options,
    )
}

export function getOrganizationUsageUsers(
    organizationId: string,
    page = 0,
    size = 50,
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageUserSummary>> {
    return getUsagePage(
        `/api/admin/usage/organizations/${uuidPathSegment(organizationId)}/users`,
        page,
        size,
        filter,
        parseUsageUserSummary,
        options,
    )
}

export function getUsageByModels(
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageModelSummary[]> {
    return getUsageArray(
        '/api/admin/usage/models',
        filter,
        parseUsageModelSummary,
        options,
    )
}

export function getOrganizationUsageModels(
    organizationId: string,
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageModelSummary[]> {
    return getUsageArray(
        `/api/admin/usage/organizations/${uuidPathSegment(organizationId)}/models`,
        filter,
        parseUsageModelSummary,
        options,
    )
}

export function getUsageDaily(
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageDailySummary[]> {
    return getUsageArray(
        '/api/admin/usage/daily',
        filter,
        parseUsageDailySummary,
        options,
    )
}

export function getOrganizationUsageDaily(
    organizationId: string,
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageDailySummary[]> {
    return getUsageArray(
        `/api/admin/usage/organizations/${uuidPathSegment(organizationId)}/daily`,
        filter,
        parseUsageDailySummary,
        options,
    )
}

export function parseUsageSummary(
    value: unknown,
    field = 'usageSummary',
): UsageSummary {
    const record = expectRecord(value, field)

    return {
        userId: expectUuid(
            record.userId,
            `${field}.userId`,
        ),
        userEmail: expectString(
            record.currentUserEmail
                ?? record.userEmail,
            `${field}.userEmail`,
            { maxLength: 320 },
        ),
        model: expectString(
            record.model,
            `${field}.model`,
            { maxLength: 100 },
        ),
        ...parseUsageAmounts(record, field),
    }
}

export function parseUsageUserSummary(
    value: unknown,
    field = 'usageUserSummary',
): UsageUserSummary {
    const record = expectRecord(value, field)

    return {
        userId: expectUuid(
            record.userId,
            `${field}.userId`,
        ),
        userEmail: expectString(
            record.currentUserEmail
                ?? record.userEmail,
            `${field}.userEmail`,
            { maxLength: 320 },
        ),
        ...parseUsageAmounts(record, field),
    }
}

export function parseUsageModelSummary(
    value: unknown,
    field = 'usageModelSummary',
): UsageModelSummary {
    const record = expectRecord(value, field)

    return {
        model: expectString(
            record.model,
            `${field}.model`,
            { maxLength: 100 },
        ),
        ...parseUsageAmounts(record, field),
    }
}

export function parseUsageDailySummary(
    value: unknown,
    field = 'usageDailySummary',
): UsageDailySummary {
    const record = expectRecord(value, field)
    const usageDate = expectString(
        record.usageDate,
        `${field}.usageDate`,
        { maxLength: 10 },
    )

    if (!/^\d{4}-\d{2}-\d{2}$/.test(usageDate)) {
        throw contractError(
            `${field}.usageDate должен быть ISO LocalDate`,
        )
    }

    const aggregationZone =
        typeof record.aggregationZone === 'string'
            ? record.aggregationZone
            : 'UTC'

    if (aggregationZone !== 'UTC') {
        throw contractError(
            `${field}.aggregationZone должен быть UTC`,
        )
    }

    return {
        usageDate,
        aggregationZone,
        ...parseUsageAmounts(record, field),
    }
}

function parseUsageAmounts(
    record: Record<string, unknown>,
    field: string,
): UsageAmounts {
    // Current production contract: nested value objects
    // { responses: {...}, usage: {...}, cost: {...} }.
    if (
        isRecord(record.usage)
        && isRecord(record.cost)
    ) {
        return parseNestedUsageAmounts(
            record,
            field,
        )
    }

    // Rolling-deployment compatibility with the previous flat DTO.
    return parseLegacyUsageAmounts(
        record,
        field,
    )
}

function parseNestedUsageAmounts(
    root: Record<string, unknown>,
    field: string,
): UsageAmounts {
    const usage = expectRecord(
        root.usage,
        `${field}.usage`,
    )
    const cost = expectRecord(
        root.cost,
        `${field}.cost`,
    )
    const responses = isRecord(root.responses)
        ? root.responses
        : {}

    const inputTokens = count(
        usage.confirmedInputTokens
            ?? usage.inputTokens,
        `${field}.usage.confirmedInputTokens`,
    )
    const outputTokens = count(
        usage.confirmedOutputTokens
            ?? usage.outputTokens,
        `${field}.usage.confirmedOutputTokens`,
    )
    const totalTokens = optionalCount(
        usage.confirmedTotalTokens
            ?? usage.totalTokens,
        `${field}.usage.confirmedTotalTokens`,
    ) ?? addCounts(inputTokens, outputTokens)

    assertSum(
        inputTokens,
        outputTokens,
        totalTokens,
        `${field}.usage.confirmedTotalTokens`,
    )

    const partialInputTokens = optionalCount(
        usage.partialKnownInputTokens
            ?? usage.partialInputTokens,
        `${field}.usage.partialKnownInputTokens`,
    ) ?? '0'
    const partialOutputTokens = optionalCount(
        usage.partialKnownOutputTokens
            ?? usage.partialOutputTokens,
        `${field}.usage.partialKnownOutputTokens`,
    ) ?? '0'
    const partialTotalTokens = optionalCount(
        usage.partialKnownTotalTokens
            ?? usage.partialTotalTokens,
        `${field}.usage.partialKnownTotalTokens`,
    ) ?? addCounts(
        partialInputTokens,
        partialOutputTokens,
    )

    assertSum(
        partialInputTokens,
        partialOutputTokens,
        partialTotalTokens,
        `${field}.usage.partialKnownTotalTokens`,
    )

    const costUsd = nonNegativeDecimal(
        cost.knownCostUsd
            ?? cost.costUsd
            ?? null,
        `${field}.cost.knownCostUsd`,
    )

    const currency = optionalString(
        cost.currency,
    ) ?? 'USD'

    const coverage: UsageCoverage = {
        assistantMessages: optionalCount(
            responses.assistantMessages
                ?? responses.assistantMessageCount,
            `${field}.responses.assistantMessages`,
        ),
        completedResponses: optionalCount(
            responses.completedResponses,
            `${field}.responses.completedResponses`,
        ),
        refusedResponses: optionalCount(
            responses.refusedResponses,
            `${field}.responses.refusedResponses`,
        ),
        incompleteResponses: optionalCount(
            responses.incompleteResponses,
            `${field}.responses.incompleteResponses`,
        ),
        failedMessages: optionalCount(
            responses.failedMessages,
            `${field}.responses.failedMessages`,
        ),
        unclassifiedResponses: optionalCount(
            responses.unclassifiedMessages
                ?? responses.unclassifiedResponses,
            `${field}.responses.unclassifiedMessages`,
        ),

        availableUsageMessages: optionalCount(
            usage.availableUsageMessages,
            `${field}.usage.availableUsageMessages`,
        ),
        partialUsageMessages: optionalCount(
            usage.partialUsageMessages,
            `${field}.usage.partialUsageMessages`,
        ),
        missingUsageMessages: optionalCount(
            usage.missingUsageMessages,
            `${field}.usage.missingUsageMessages`,
        ),
        usageNotApplicableMessages: optionalCount(
            usage.usageNotApplicableMessages,
            `${field}.usage.usageNotApplicableMessages`,
        ),

        pricedMessages: optionalCount(
            cost.pricedMessages,
            `${field}.cost.pricedMessages`,
        ),
        freeMessages: optionalCount(
            cost.freeMessages,
            `${field}.cost.freeMessages`,
        ),
        unpricedMessages: optionalCount(
            cost.unpricedMessages,
            `${field}.cost.unpricedMessages`,
        ),
        pricingFailedMessages: optionalCount(
            cost.pricingFailedMessages,
            `${field}.cost.pricingFailedMessages`,
        ),
        pricingNotApplicableMessages: optionalCount(
            cost.pricingNotApplicableMessages,
            `${field}.cost.pricingNotApplicableMessages`,
        ),

        usageComplete: optionalBoolean(
            usage.usageComplete,
            `${field}.usage.usageComplete`,
        ) ?? deriveUsageComplete(usage),
        pricingComplete: optionalBoolean(
            cost.pricingComplete,
            `${field}.cost.pricingComplete`,
        ) ?? derivePricingComplete(cost),
    }

    validateCoverage(
        coverage,
        costUsd,
        field,
    )

    return {
        inputTokens,
        outputTokens,
        totalTokens,
        partialInputTokens,
        partialOutputTokens,
        partialTotalTokens,
        costUsd,
        currency,
        coverage,
    }
}

function parseLegacyUsageAmounts(
    record: Record<string, unknown>,
    field: string,
): UsageAmounts {
    const inputTokens = count(
        record.inputTokens,
        `${field}.inputTokens`,
    )
    const outputTokens = count(
        record.outputTokens,
        `${field}.outputTokens`,
    )
    const totalTokens = count(
        record.totalTokens,
        `${field}.totalTokens`,
    )

    assertSum(
        inputTokens,
        outputTokens,
        totalTokens,
        `${field}.totalTokens`,
    )

    const costUsd = nonNegativeDecimal(
        record.costUsd ?? null,
        `${field}.costUsd`,
    )

    const coverage: UsageCoverage = {
        assistantMessages: optionalCount(
            record.assistantMessageCount
                ?? record.assistantMessages,
            `${field}.assistantMessageCount`,
        ),
        completedResponses: optionalCount(
            record.completedResponses,
            `${field}.completedResponses`,
        ),
        refusedResponses: optionalCount(
            record.refusedResponses,
            `${field}.refusedResponses`,
        ),
        incompleteResponses: optionalCount(
            record.incompleteResponses,
            `${field}.incompleteResponses`,
        ),
        failedMessages: optionalCount(
            record.failedMessages,
            `${field}.failedMessages`,
        ),
        unclassifiedResponses: optionalCount(
            record.unclassifiedMessages,
            `${field}.unclassifiedMessages`,
        ),

        availableUsageMessages: optionalCount(
            record.availableUsageMessageCount
                ?? record.availableUsageMessages,
            `${field}.availableUsageMessageCount`,
        ),
        partialUsageMessages: optionalCount(
            record.partialUsageMessageCount
                ?? record.partialUsageMessages,
            `${field}.partialUsageMessageCount`,
        ),
        missingUsageMessages: optionalCount(
            record.missingUsageMessageCount
                ?? record.missingUsageMessages,
            `${field}.missingUsageMessageCount`,
        ),
        usageNotApplicableMessages: optionalCount(
            record.usageNotApplicableMessageCount
                ?? record.usageNotApplicableMessages,
            `${field}.usageNotApplicableMessageCount`,
        ),

        pricedMessages: optionalCount(
            record.pricedMessageCount
                ?? record.pricedMessages,
            `${field}.pricedMessageCount`,
        ),
        freeMessages: optionalCount(
            record.freeMessageCount
                ?? record.freeMessages,
            `${field}.freeMessageCount`,
        ),
        unpricedMessages: optionalCount(
            record.unpricedMessageCount
                ?? record.unpricedMessages,
            `${field}.unpricedMessageCount`,
        ),
        pricingFailedMessages: optionalCount(
            record.pricingFailedMessageCount
                ?? record.pricingFailedMessages,
            `${field}.pricingFailedMessageCount`,
        ),
        pricingNotApplicableMessages: optionalCount(
            record.pricingNotApplicableMessageCount
                ?? record.pricingNotApplicableMessages,
            `${field}.pricingNotApplicableMessageCount`,
        ),

        usageComplete: optionalBoolean(
            record.usageComplete,
            `${field}.usageComplete`,
        ) ?? deriveUsageComplete(record),
        pricingComplete: optionalBoolean(
            record.pricingComplete,
            `${field}.pricingComplete`,
        ) ?? derivePricingComplete(record),
    }

    validateCoverage(coverage, costUsd, field)

    return {
        inputTokens,
        outputTokens,
        totalTokens,
        partialInputTokens: optionalCount(
            record.partialKnownInputTokens,
            `${field}.partialKnownInputTokens`,
        ) ?? '0',
        partialOutputTokens: optionalCount(
            record.partialKnownOutputTokens,
            `${field}.partialKnownOutputTokens`,
        ) ?? '0',
        partialTotalTokens: optionalCount(
            record.partialKnownTotalTokens,
            `${field}.partialKnownTotalTokens`,
        ) ?? '0',
        costUsd,
        currency: optionalString(
            record.currency,
        ) ?? 'USD',
        coverage,
    }
}

async function getUsagePage<T>(
    path: string,
    page: number,
    size: number,
    filter: UsageSummaryFilter,
    parser: (value: unknown, field?: string) => T,
    options: RequestOptions,
): Promise<PageResponse<T>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(size, 50, 100),
        dateFrom: normalizeOptionalString(filter.dateFrom),
        dateTo: normalizeOptionalString(filter.dateTo),
        model: normalizeOptionalString(filter.model),
    })

    const response = await apiRequest<unknown>(
        `${path}${query}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    return parseUsagePageResponse(response, parser)
}

export function parseUsagePageResponse<T>(
    value: unknown,
    parser: (value: unknown, field?: string) => T,
): PageResponse<T> {
    const record = expectRecord(value, 'usagePage')

    // Usage reports intentionally use Slice to avoid an expensive COUNT query.
    // Adapt that contract to the shared pagination view without pretending that
    // an exact total is known. Older Page responses remain supported.
    if (
        Array.isArray(record.content)
        && typeof record.hasNext === 'boolean'
        && typeof record.hasPrevious === 'boolean'
        && typeof record.page === 'number'
        && typeof record.size === 'number'
        && record.totalElements === undefined
    ) {
        const page = normalizePage(record.page)
        const size = normalizePageSize(record.size, 50, 100)
        const content = record.content.map(
            (item, index) => parser(
                item,
                `usagePage.content[${index}]`,
            ),
        )
        const totalPages = record.hasNext
            ? page + 2
            : page + (content.length > 0 ? 1 : 0)

        return {
            content,
            page,
            size,
            totalPages,
            totalElements: page * size + content.length
                + (record.hasNext ? 1 : 0),
        }
    }

    return parsePageResponse(
        value,
        (item, field) => parser(item, field),
    )
}

async function getUsageArray<T>(
    path: string,
    filter: UsageDateRangeFilter,
    parser: (value: unknown, field?: string) => T,
    options: RequestOptions,
): Promise<T[]> {
    const query = buildQueryString({
        dateFrom: normalizeOptionalString(filter.dateFrom),
        dateTo: normalizeOptionalString(filter.dateTo),
    })

    const response = await apiRequest<unknown>(
        `${path}${query}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    if (!Array.isArray(response)) {
        throw contractError(
            'usage response должен быть массивом',
        )
    }

    return response.map(
        (value, index) =>
            parser(
                value,
                `usage[${index}]`,
            ),
    )
}


function deriveUsageComplete(
    record: Record<string, unknown>,
): boolean | null {
    const partial = optionalCount(
        record.partialUsageMessages
            ?? record.partialUsageMessageCount,
        'usage.partialUsageMessages',
    )
    const missing = optionalCount(
        record.missingUsageMessages
            ?? record.missingUsageMessageCount,
        'usage.missingUsageMessages',
    )

    if (partial === null || missing === null) {
        return null
    }

    return partial === '0' && missing === '0'
}

function derivePricingComplete(
    record: Record<string, unknown>,
): boolean | null {
    const unpriced = optionalCount(
        record.unpricedMessages
            ?? record.unpricedMessageCount,
        'cost.unpricedMessages',
    )
    const failed = optionalCount(
        record.pricingFailedMessages
            ?? record.pricingFailedMessageCount,
        'cost.pricingFailedMessages',
    )

    if (unpriced === null || failed === null) {
        return null
    }

    return unpriced === '0' && failed === '0'
}

function validateCoverage(
    coverage: UsageCoverage,
    costUsd: string | null,
    field: string,
): void {
    if (
        coverage.pricingComplete === true
        && coverage.assistantMessages !== null
        && coverage.assistantMessages !== '0'
        && costUsd === null
    ) {
        throw contractError(
            `${field}: knownCostUsd отсутствует при complete pricing coverage`,
        )
    }
}

function assertSum(
    left: string,
    right: string,
    total: string,
    field: string,
): void {
    if (
        BigInt(left) + BigInt(right)
        !== BigInt(total)
    ) {
        throw contractError(
            `${field} не равен сумме составляющих`,
        )
    }
}

function addCounts(
    left: string,
    right: string,
): string {
    return (
        BigInt(left) + BigInt(right)
    ).toString()
}

function count(
    value: unknown,
    field: string,
): string {
    const parsed = optionalCount(value, field)

    if (parsed === null) {
        throw contractError(
            `${field} не должен быть null`,
        )
    }

    return parsed
}

function optionalCount(
    value: unknown,
    field: string,
): string | null {
    if (value === undefined || value === null) {
        return null
    }

    if (
        typeof value === 'number'
        && Number.isSafeInteger(value)
        && value >= 0
    ) {
        return String(value)
    }

    if (
        typeof value === 'string'
        && /^(0|[1-9]\d*)$/.test(value)
    ) {
        return value
    }

    throw contractError(
        `${field} должен быть неотрицательным целым числом`,
    )
}

function nonNegativeDecimal(
    value: unknown,
    field: string,
): string | null {
    if (value === undefined || value === null) {
        return null
    }

    const normalized = typeof value === 'number'
        ? String(value)
        : typeof value === 'string'
            ? value
            : null

    if (
        normalized === null
        || !/^(0|[1-9]\d*)(\.\d+)?$/.test(
            normalized,
        )
    ) {
        throw contractError(
            `${field} должен быть неотрицательным decimal`,
        )
    }

    return normalized
}

function optionalBoolean(
    value: unknown,
    field: string,
): boolean | null {
    if (value === undefined || value === null) {
        return null
    }

    if (typeof value !== 'boolean') {
        throw contractError(
            `${field} должен быть boolean`,
        )
    }

    return value
}

function optionalString(
    value: unknown,
): string | null {
    return typeof value === 'string'
        && value.trim().length > 0
        ? value.trim()
        : null
}

function normalizeOptionalString(
    value: string | undefined,
): string | undefined {
    const normalized = value?.trim()
    return normalized || undefined
}

function isRecord(
    value: unknown,
): value is Record<string, unknown> {
    return typeof value === 'object'
        && value !== null
        && !Array.isArray(value)
}

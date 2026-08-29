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
    expectBoolean,
    expectRecord,
    expectString,
    expectUuid,
    parseDecimalString,
    parsePageResponse,
} from './runtime'
import type { PageResponse } from '../utils/page'

function normalizeOptionalString(
    value: string | undefined,
): string | undefined {
    const normalized = value?.trim()
    return normalized || undefined
}

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

type RequestOptions = {
    signal?: AbortSignal
}

export async function getUsageSummary(
    page: number,
    size: number,
    filter: UsageFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        `/api/admin/usage/summary${usagePagedQuery(
            page,
            size,
            filter,
        )}`,
        parseUsageSummary,
        options,
    )
}

export async function getUsageByUsers(
    page: number,
    size: number,
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageUserSummary>> {
    return getUsagePage(
        `/api/admin/usage/users${usagePagedQuery(
            page,
            size,
            filter,
        )}`,
        parseUsageUserSummary,
        options,
    )
}

export async function getUsageByUser(
    userId: string,
    page: number,
    size: number,
    filter: UsageFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        `/api/admin/usage/by-user/${uuidPathSegment(
            userId,
        )}${usagePagedQuery(
            page,
            size,
            filter,
        )}`,
        parseUsageSummary,
        options,
    )
}

export async function getUsageByOrganization(
    organizationId: string,
    page: number,
    size: number,
    filter: UsageFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageSummary>> {
    return getUsagePage(
        `/api/admin/usage/by-organization/${uuidPathSegment(
            organizationId,
        )}${usagePagedQuery(
            page,
            size,
            filter,
        )}`,
        parseUsageSummary,
        options,
    )
}

export async function getUsageByModels(
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageModelSummary[]> {
    return getUsageArray(
        `/api/admin/usage/models${usageQuery(
            filter,
        )}`,
        parseUsageModelSummary,
        options,
    )
}

export async function getUsageDaily(
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageDailySummary[]> {
    return getUsageArray(
        `/api/admin/usage/daily${usageQuery(
            filter,
        )}`,
        parseUsageDailySummary,
        options,
    )
}


export function parseUsageSummary(
    value: unknown,
    field = 'usageSummary',
): UsageSummary {
    const record = expectRecord(
        value,
        field,
    )

    return {
        userId: expectUuid(
            record.userId,
            `${field}.userId`,
        ),
        userEmail: expectString(
            record.userEmail,
            `${field}.userEmail`,
            {
                maxLength: 320,
            },
        ),
        model: expectString(
            record.model,
            `${field}.model`,
            {
                maxLength: 100,
            },
        ),
        ...parseUsageAmounts(
            record,
            field,
        ),
    }
}

export function parseUsageUserSummary(
    value: unknown,
    field = 'usageUserSummary',
): UsageUserSummary {
    const record = expectRecord(
        value,
        field,
    )

    return {
        userId: expectUuid(
            record.userId,
            `${field}.userId`,
        ),
        userEmail: expectString(
            record.userEmail,
            `${field}.userEmail`,
            {
                maxLength: 320,
            },
        ),
        ...parseUsageAmounts(
            record,
            field,
        ),
    }
}

export function parseUsageModelSummary(
    value: unknown,
    field = 'usageModelSummary',
): UsageModelSummary {
    const record = expectRecord(
        value,
        field,
    )

    return {
        model: expectString(
            record.model,
            `${field}.model`,
            {
                maxLength: 100,
            },
        ),
        ...parseUsageAmounts(
            record,
            field,
        ),
    }
}

export function parseUsageDailySummary(
    value: unknown,
    field = 'usageDailySummary',
): UsageDailySummary {
    const record = expectRecord(
        value,
        field,
    )

    return {
        usageDate:
            parseDateOnly(
                record.usageDate,
                `${field}.usageDate`,
            ),
        ...parseUsageAmounts(
            record,
            field,
        ),
    }
}


async function getUsagePage<T>(
    url: string,
    parser: (
        value: unknown,
        field: string,
    ) => T,
    options: RequestOptions,
): Promise<PageResponse<T>> {
    const response = await apiRequest<unknown>(
        url,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    return parsePageResponse(
        response,
        parser,
    )
}

async function getUsageArray<T>(
    url: string,
    parser: (
        value: unknown,
        field: string,
    ) => T,
    options: RequestOptions,
): Promise<T[]> {
    const response = await apiRequest<unknown>(
        url,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    return parseArray(
        response,
        parser,
        'usageResponse',
    )
}


function parseArray<T>(
    value: unknown,
    parser: (
        value: unknown,
        field: string,
    ) => T,
    field: string,
): T[] {
    if (!Array.isArray(value)) {
        throw contractError(
            `${field} должен быть массивом`,
        )
    }

    return value.map(
        (item, index) =>
            parser(
                item,
                `${field}[${index}]`,
            ),
    )
}


function usagePagedQuery(
    page: number,
    size: number,
    filter:
        | UsageFilter
        | UsageDateRangeFilter,
): string {
    return buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(
            size,
            50,
            200,
        ),
        ...usageQueryValues(filter),
    })
}

function usageQuery(
    filter:
        | UsageFilter
        | UsageDateRangeFilter,
): string {
    return buildQueryString(
        usageQueryValues(filter),
    )
}

function usageQueryValues(
    filter:
        | UsageFilter
        | UsageDateRangeFilter,
): Record<
    string,
    string | undefined
> {
    return {
        dateFrom:
            normalizeOptionalString(
                filter.dateFrom,
            ),
        dateTo:
            normalizeOptionalString(
                filter.dateTo,
            ),
        model:
            'model' in filter
                ? normalizeOptionalString(
                    filter.model,
                )
                : undefined,
    }
}


function parseUsageAmounts(
    record: Record<string, unknown>,
    field: string,
): {
    inputTokens: string
    outputTokens: string
    totalTokens: string
    costUsd: string | null
    coverage: UsageCoverage
} {
    const inputTokens =
        parseNonNegativeIntegerString(
            record.inputTokens,
            `${field}.inputTokens`,
        )

    const outputTokens =
        parseNonNegativeIntegerString(
            record.outputTokens,
            `${field}.outputTokens`,
        )

    const totalTokens =
        parseNonNegativeIntegerString(
            record.totalTokens,
            `${field}.totalTokens`,
        )

    const costUsd =
        parseNonNegativeDecimalString(
            record.costUsd ?? null,
            `${field}.costUsd`,
        )

    if (
        BigInt(inputTokens)
        + BigInt(outputTokens)
        !== BigInt(totalTokens)
    ) {
        throw contractError(
            `${field}.totalTokens не равен inputTokens + outputTokens`,
        )
    }

    const coverage =
        parseUsageCoverage(
            record,
            field,
        )

    if (
        coverage.pricingComplete === true
        && coverage.assistantMessages !== null
        && coverage.assistantMessages !== '0'
        && costUsd === null
    ) {
        throw contractError(
            `${field}.costUsd отсутствует при complete pricing coverage`,
        )
    }

    return {
        inputTokens,
        outputTokens,
        totalTokens,
        costUsd,
        coverage,
    }
}

function parseUsageCoverage(
    record: Record<string, unknown>,
    field: string,
): UsageCoverage {
    const assistantMessages =
        parseOptionalCount(
            record.assistantMessageCount
                ?? record.assistantMessages,
            `${field}.assistantMessageCount`,
        )

    const availableUsageMessages =
        parseOptionalCount(
            record.availableUsageMessageCount
                ?? record.availableUsageMessages,
            `${field}.availableUsageMessageCount`,
        )

    const partialUsageMessages =
        parseOptionalCount(
            record.partialUsageMessageCount
                ?? record.partialUsageMessages,
            `${field}.partialUsageMessageCount`,
        )

    const missingUsageMessages =
        parseOptionalCount(
            record.missingUsageMessageCount
                ?? record.missingUsageMessages,
            `${field}.missingUsageMessageCount`,
        )

    const usageNotApplicableMessages =
        parseOptionalCount(
            record.usageNotApplicableMessageCount
                ?? record.usageNotApplicableMessages,
            `${field}.usageNotApplicableMessageCount`,
        )

    const pricedMessages =
        parseOptionalCount(
            record.pricedMessageCount
                ?? record.pricedMessages,
            `${field}.pricedMessageCount`,
        )

    const freeMessages =
        parseOptionalCount(
            record.freeMessageCount
                ?? record.freeMessages,
            `${field}.freeMessageCount`,
        )

    const unpricedMessages =
        parseOptionalCount(
            record.unpricedMessageCount
                ?? record.unpricedMessages,
            `${field}.unpricedMessageCount`,
        )

    const pricingFailedMessages =
        parseOptionalCount(
            record.pricingFailedMessageCount
                ?? record.pricingFailedMessages,
            `${field}.pricingFailedMessageCount`,
        )

    const pricingNotApplicableMessages =
        parseOptionalCount(
            record.pricingNotApplicableMessageCount
                ?? record.pricingNotApplicableMessages,
            `${field}.pricingNotApplicableMessageCount`,
        )

    const ambiguousProviderOperations =
        parseOptionalCount(
            record.ambiguousProviderOperationCount
                ?? record.ambiguousProviderOperations,
            `${field}.ambiguousProviderOperationCount`,
        )

    const explicitUsageComplete =
        parseOptionalBoolean(
            record.usageComplete,
            `${field}.usageComplete`,
        )

    const explicitPricingComplete =
        parseOptionalBoolean(
            record.pricingComplete,
            `${field}.pricingComplete`,
        )

    assertCoverageTotal(
        assistantMessages,
        [
            availableUsageMessages,
            partialUsageMessages,
            missingUsageMessages,
            usageNotApplicableMessages,
        ],
        `${field}.usageCoverage`,
    )

    assertCoverageTotal(
        assistantMessages,
        [
            pricedMessages,
            freeMessages,
            unpricedMessages,
            pricingFailedMessages,
            pricingNotApplicableMessages,
        ],
        `${field}.pricingCoverage`,
    )

    const inferredUsageComplete =
        inferUsageComplete({
            assistantMessages,
            availableUsageMessages,
            partialUsageMessages,
            missingUsageMessages,
            usageNotApplicableMessages,
        })

    const inferredPricingComplete =
        inferPricingComplete({
            assistantMessages,
            pricedMessages,
            freeMessages,
            unpricedMessages,
            pricingFailedMessages,
            pricingNotApplicableMessages,
        })

    if (
        explicitUsageComplete !== null
        && inferredUsageComplete !== null
        && explicitUsageComplete
            !== inferredUsageComplete
    ) {
        throw contractError(
            `${field}.usageComplete противоречит coverage counters`,
        )
    }

    if (
        explicitPricingComplete !== null
        && inferredPricingComplete !== null
        && explicitPricingComplete
            !== inferredPricingComplete
    ) {
        throw contractError(
            `${field}.pricingComplete противоречит coverage counters`,
        )
    }

    const usageComplete =
        explicitUsageComplete
        ?? inferredUsageComplete

    const pricingComplete =
        explicitPricingComplete
        ?? inferredPricingComplete

    return {
        assistantMessages,

        availableUsageMessages,
        partialUsageMessages,
        missingUsageMessages,
        usageNotApplicableMessages,

        pricedMessages,
        freeMessages,
        unpricedMessages,
        pricingFailedMessages,
        pricingNotApplicableMessages,

        ambiguousProviderOperations,

        usageComplete,
        pricingComplete,
    }
}

function assertCoverageTotal(
    assistantMessages: string | null,
    categories: (string | null)[],
    field: string,
): void {
    if (
        assistantMessages === null
        || categories.some(
            (value) => value === null,
        )
    ) {
        return
    }

    const total = categories.reduce(
        (sum, value) =>
            sum + BigInt(value ?? '0'),
        0n,
    )

    if (
        total !== BigInt(
            assistantMessages,
        )
    ) {
        throw contractError(
            `${field} не согласован с assistantMessageCount`,
        )
    }
}

function inferUsageComplete(
    coverage: Pick<
        UsageCoverage,
        | 'assistantMessages'
        | 'availableUsageMessages'
        | 'partialUsageMessages'
        | 'missingUsageMessages'
        | 'usageNotApplicableMessages'
    >,
): boolean | null {
    if (
        coverage.assistantMessages === null
        || coverage.availableUsageMessages === null
        || coverage.partialUsageMessages === null
        || coverage.missingUsageMessages === null
        || coverage.usageNotApplicableMessages === null
    ) {
        return null
    }

    return coverage.partialUsageMessages === '0'
        && coverage.missingUsageMessages === '0'
}

function inferPricingComplete(
    coverage: Pick<
        UsageCoverage,
        | 'assistantMessages'
        | 'pricedMessages'
        | 'freeMessages'
        | 'unpricedMessages'
        | 'pricingFailedMessages'
        | 'pricingNotApplicableMessages'
    >,
): boolean | null {
    if (
        coverage.assistantMessages === null
        || coverage.pricedMessages === null
        || coverage.freeMessages === null
        || coverage.unpricedMessages === null
        || coverage.pricingFailedMessages === null
        || coverage.pricingNotApplicableMessages === null
    ) {
        return null
    }

    return coverage.unpricedMessages === '0'
        && coverage.pricingFailedMessages === '0'
}

function parseOptionalBoolean(
    value: unknown,
    field: string,
): boolean | null {
    if (
        value === undefined
        || value === null
    ) {
        return null
    }

    return expectBoolean(
        value,
        field,
    )
}

function parseOptionalCount(
    value: unknown,
    field: string,
): string | null {
    if (
        value === undefined
        || value === null
    ) {
        return null
    }

    return parseNonNegativeIntegerString(
        value,
        field,
    )
}

function parseNonNegativeIntegerString(
    value: unknown,
    field: string,
): string {
    if (
        typeof value === 'number'
    ) {
        if (
            !Number.isSafeInteger(value)
            || value < 0
        ) {
            throw contractError(
                `${field} должен быть неотрицательным safe integer или decimal string`,
            )
        }

        return String(value)
    }

    if (
        typeof value === 'string'
        && /^(?:0|[1-9]\d*)$/.test(
            value,
        )
    ) {
        return value
    }

    throw contractError(
        `${field} должен быть неотрицательным integer string`,
    )
}

function parseNonNegativeDecimalString(
    value: unknown,
    field: string,
): string | null {
    if (value === null) {
        return null
    }

    if (typeof value !== 'string') {
        throw contractError(
            `${field} должен быть decimal string или null`,
        )
    }

    const decimal =
        parseDecimalString(
            value,
            field,
        )

    if (decimal === null) {
        return null
    }

    if (
        decimal.startsWith('-')
    ) {
        throw contractError(
            `${field} не может быть отрицательным`,
        )
    }

    const fraction =
        decimal.split('.')[1] ?? ''

    if (fraction.length > 12) {
        throw contractError(
            `${field} не должен иметь scale больше 12`,
        )
    }

    return decimal
}

function parseDateOnly(
    value: unknown,
    field: string,
): string {
    const dateValue = expectString(
        value,
        field,
    )

    const match =
        /^(\d{4})-(\d{2})-(\d{2})$/.exec(
            dateValue,
        )

    if (!match) {
        throw contractError(
            `${field} должен иметь формат YYYY-MM-DD`,
        )
    }

    const year = Number(match[1])
    const month = Number(match[2])
    const day = Number(match[3])

    const date = new Date(
        Date.UTC(
            year,
            month - 1,
            day,
        ),
    )

    if (
        date.getUTCFullYear() !== year
        || date.getUTCMonth()
            !== month - 1
        || date.getUTCDate() !== day
    ) {
        throw contractError(
            `${field} содержит невозможную календарную дату`,
        )
    }

    return dateValue
}


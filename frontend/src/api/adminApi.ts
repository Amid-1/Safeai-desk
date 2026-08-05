// ============================================================
// frontend/src/api/adminApi.ts
// ============================================================

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
    expectInstant,
    expectNullableString,
    expectNullableUuid,
    expectRecord,
    expectString,
    expectUuid,
    isRecord,
    parseDecimalString,
    parsePageResponse,
} from './runtime'
import type {
    PageResponse,
} from '../utils/page'

export type JsonPrimitive =
    | string
    | number
    | boolean
    | null

export type JsonValue =
    | JsonPrimitive
    | JsonValue[]
    | {
        [key: string]: JsonValue
    }

export type AuditEvent = {
    id: string

    targetOrganizationId: string
    targetOrganizationName: string | null

    actorUserId: string | null
    actorOrganizationId: string | null
    actorEmail: string | null
    actorDisplayName: string | null

    eventType: string

    /**
     * Defense-in-depth представление.
     * Backend всё равно обязан применять event-specific allowlist.
     */
    details: Record<string, JsonValue>
    detailsTruncated: boolean
    detailsInvalid: boolean

    createdAt: string
}

export type AuditEventFilter = {
    eventType?: string

    actorUserId?: string
    actorEmail?: string

    dateFrom?: string
    dateTo?: string

    targetOrganizationId?: string
}

export type AuditActorDirectoryItem = {
    actorUserId: string | null
    actorOrganizationId: string | null
    actorEmail: string | null
    actorDisplayName: string | null
}

export type AuditTargetOrganizationDirectoryItem = {
    targetOrganizationId: string
    targetOrganizationName: string | null
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

const MAX_AUDIT_EVENT_TYPE_LENGTH = 128
const MAX_AUDIT_EMAIL_LENGTH = 320
const MAX_AUDIT_DISPLAY_NAME_LENGTH = 255

const MAX_AUDIT_DETAILS_DEPTH = 8
const MAX_AUDIT_DETAILS_NODES = 2_000
const MAX_AUDIT_DETAILS_KEYS = 200
const MAX_AUDIT_DETAILS_KEY_LENGTH = 256
const MAX_AUDIT_DETAILS_ARRAY_ITEMS = 200
const MAX_AUDIT_DETAILS_STRING_LENGTH = 4_096
const MAX_AUDIT_DETAILS_TOTAL_CHARACTERS =
    64 * 1_024

const SENSITIVE_AUDIT_KEYS = new Set([
    'authorization',
    'proxyauthorization',
    'cookie',
    'setcookie',
    'password',
    'passwordhash',
    'secret',
    'clientsecret',
    'apikey',
    'accesstoken',
    'refreshtoken',
    'idtoken',
    'privatekey',
    'credential',
    'credentials',
    'sessiontoken',
])

export async function getAuditEvents(
    page = 0,
    size = 50,
    filter: AuditEventFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<AuditEvent>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(
            size,
            50,
            100,
        ),
        eventType:
            normalizeOptionalString(
                filter.eventType,
            ),

        // Текущий backend использует старые query names.
        // Во frontend-модели они семантически называются actor/target.
        userId:
            filter.actorUserId
                ? uuidPathSegment(
                    filter.actorUserId,
                )
                : undefined,
        userEmail:
            normalizeOptionalString(
                filter.actorEmail,
            ),
        organizationId:
            filter.targetOrganizationId
                ? uuidPathSegment(
                    filter.targetOrganizationId,
                )
                : undefined,

        dateFrom:
            normalizeOptionalString(
                filter.dateFrom,
            ),
        dateTo:
            normalizeOptionalString(
                filter.dateTo,
            ),
    })

    const response = await apiRequest<unknown>(
        `/api/admin/audit-events${query}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    return parsePageResponse(
        response,
        parseAuditEvent,
    )
}

export async function getAuditEventTypes(
    options: RequestOptions = {},
): Promise<string[]> {
    const response = await apiRequest<unknown>(
        '/api/admin/audit-event-types',
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    if (!Array.isArray(response)) {
        throw contractError(
            'auditEventTypes должен быть массивом',
        )
    }

    const unique = new Set<string>()

    response.forEach((value, index) => {
        unique.add(
            parseAuditEventType(
                value,
                `auditEventTypes[${index}]`,
            ),
        )
    })

    return [...unique].sort()
}

export async function searchAuditActors(
    query: string,
    targetOrganizationId?: string,
    limit = 20,
    options: RequestOptions = {},
): Promise<AuditActorDirectoryItem[]> {
    const search = buildQueryString({
        query:
            normalizeOptionalString(query),
        organizationId:
            targetOrganizationId
                ? uuidPathSegment(
                    targetOrganizationId,
                )
                : undefined,
        limit: normalizePageSize(
            limit,
            20,
            50,
        ),
    })

    const response = await apiRequest<unknown>(
        `/api/admin/audit-actors${search}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    return parseArray(
        response,
        parseAuditActorDirectoryItem,
        'auditActors',
    )
}

export async function searchAuditTargetOrganizations(
    query: string,
    limit = 20,
    options: RequestOptions = {},
): Promise<AuditTargetOrganizationDirectoryItem[]> {
    const search = buildQueryString({
        query:
            normalizeOptionalString(query),
        limit: normalizePageSize(
            limit,
            20,
            50,
        ),
    })

    const response = await apiRequest<unknown>(
        `/api/admin/audit-organizations${search}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.report,
        },
    )

    return parseArray(
        response,
        parseAuditTargetOrganizationDirectoryItem,
        'auditOrganizations',
    )
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
        `/api/admin/usage/users/${uuidPathSegment(
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
        `/api/admin/usage/organizations/${uuidPathSegment(
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

/**
 * Production organization-scoped aggregate endpoints.
 * Никакой client-side финансовой агрегации detail rows.
 */
export async function getOrganizationUsageUsers(
    organizationId: string,
    page: number,
    size: number,
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<PageResponse<UsageUserSummary>> {
    return getUsagePage(
        `/api/admin/usage/organizations/${uuidPathSegment(
            organizationId,
        )}/users${usagePagedQuery(
            page,
            size,
            filter,
        )}`,
        parseUsageUserSummary,
        options,
    )
}

export async function getOrganizationUsageModels(
    organizationId: string,
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageModelSummary[]> {
    return getUsageArray(
        `/api/admin/usage/organizations/${uuidPathSegment(
            organizationId,
        )}/models${usageQuery(
            filter,
        )}`,
        parseUsageModelSummary,
        options,
    )
}

export async function getOrganizationUsageDaily(
    organizationId: string,
    filter: UsageDateRangeFilter = {},
    options: RequestOptions = {},
): Promise<UsageDailySummary[]> {
    return getUsageArray(
        `/api/admin/usage/organizations/${uuidPathSegment(
            organizationId,
        )}/daily${usageQuery(
            filter,
        )}`,
        parseUsageDailySummary,
        options,
    )
}

export function parseAuditEvent(
    value: unknown,
    field = 'auditEvent',
): AuditEvent {
    const record = expectRecord(
        value,
        field,
    )

    const detailsResult =
        parseAuditDetails(
            record.details,
        )

    return {
        id: expectUuid(
            record.id,
            `${field}.id`,
        ),

        targetOrganizationId:
            expectUuid(
                record.targetOrganizationId
                    ?? record.organizationId,
                `${field}.targetOrganizationId`,
            ),

        targetOrganizationName:
            expectNullableString(
                record.targetOrganizationName
                    ?? record.organizationName
                    ?? null,
                `${field}.targetOrganizationName`,
                {
                    maxLength: 255,
                },
            ),

        actorUserId:
            expectNullableUuid(
                record.actorUserId
                    ?? record.userId
                    ?? null,
                `${field}.actorUserId`,
            ),

        actorOrganizationId:
            expectNullableUuid(
                record.actorOrganizationId
                    ?? null,
                `${field}.actorOrganizationId`,
            ),

        actorEmail:
            expectNullableString(
                record.actorEmail
                    ?? record.userEmail
                    ?? null,
                `${field}.actorEmail`,
                {
                    maxLength:
                        MAX_AUDIT_EMAIL_LENGTH,
                },
            ),

        actorDisplayName:
            expectNullableString(
                record.actorDisplayName
                    ?? record.userDisplayName
                    ?? null,
                `${field}.actorDisplayName`,
                {
                    maxLength:
                        MAX_AUDIT_DISPLAY_NAME_LENGTH,
                },
            ),

        eventType:
            parseAuditEventType(
                record.eventType,
                `${field}.eventType`,
            ),

        details:
            detailsResult.details,
        detailsTruncated:
            detailsResult.truncated,
        detailsInvalid:
            detailsResult.invalid,

        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
    }
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

export function parseAuditDetails(
    value: unknown,
): {
    details: Record<string, JsonValue>
    truncated: boolean
    invalid: boolean
} {
    if (!isRecord(value)) {
        return {
            details: {},
            truncated: false,
            invalid:
                value !== undefined
                && value !== null,
        }
    }

    const state = {
        nodes: 0,
        characters: 0,
        truncated: false,
    }

    const parsed = sanitizeJsonValue(
        value,
        0,
        state,
    )

    if (!isRecord(parsed)) {
        return {
            details: {},
            truncated:
                state.truncated,
            invalid: true,
        }
    }

    return {
        details:
            parsed as Record<
                string,
                JsonValue
            >,
        truncated:
            state.truncated,
        invalid: false,
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

function normalizeOptionalString(
    value: string | undefined,
): string | undefined {
    const normalized = value?.trim()

    return normalized || undefined
}

function parseAuditEventType(
    value: unknown,
    field: string,
): string {
    const eventType = expectString(
        value,
        field,
        {
            maxLength:
                MAX_AUDIT_EVENT_TYPE_LENGTH,
        },
    )

    if (
        !/^[A-Z][A-Z0-9_]*$/.test(
            eventType,
        )
    ) {
        throw contractError(
            `${field} имеет некорректный формат`,
        )
    }

    return eventType
}

function parseAuditActorDirectoryItem(
    value: unknown,
    field: string,
): AuditActorDirectoryItem {
    const record = expectRecord(
        value,
        field,
    )

    return {
        actorUserId:
            expectNullableUuid(
                record.actorUserId
                    ?? record.userId
                    ?? null,
                `${field}.actorUserId`,
            ),
        actorOrganizationId:
            expectNullableUuid(
                record.actorOrganizationId
                    ?? null,
                `${field}.actorOrganizationId`,
            ),
        actorEmail:
            expectNullableString(
                record.actorEmail
                    ?? record.userEmail
                    ?? null,
                `${field}.actorEmail`,
                {
                    maxLength:
                        MAX_AUDIT_EMAIL_LENGTH,
                },
            ),
        actorDisplayName:
            expectNullableString(
                record.actorDisplayName
                    ?? record.userDisplayName
                    ?? null,
                `${field}.actorDisplayName`,
                {
                    maxLength:
                        MAX_AUDIT_DISPLAY_NAME_LENGTH,
                },
            ),
    }
}

function parseAuditTargetOrganizationDirectoryItem(
    value: unknown,
    field: string,
): AuditTargetOrganizationDirectoryItem {
    const record = expectRecord(
        value,
        field,
    )

    return {
        targetOrganizationId:
            expectUuid(
                record.targetOrganizationId
                    ?? record.organizationId
                    ?? record.id,
                `${field}.targetOrganizationId`,
            ),
        targetOrganizationName:
            expectNullableString(
                record.targetOrganizationName
                    ?? record.organizationName
                    ?? record.name
                    ?? null,
                `${field}.targetOrganizationName`,
                {
                    maxLength: 255,
                },
            ),
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

function sanitizeJsonValue(
    value: unknown,
    depth: number,
    state: {
        nodes: number
        characters: number
        truncated: boolean
    },
): JsonValue {
    state.nodes += 1

    if (
        state.nodes
            > MAX_AUDIT_DETAILS_NODES
        || depth
            > MAX_AUDIT_DETAILS_DEPTH
    ) {
        state.truncated = true
        return '[TRUNCATED]'
    }

    if (
        value === null
        || typeof value === 'boolean'
    ) {
        return value
    }

    if (typeof value === 'string') {
        const remainingCharacters =
            Math.max(
                0,
                MAX_AUDIT_DETAILS_TOTAL_CHARACTERS
                - state.characters,
            )

        const allowedLength =
            Math.min(
                MAX_AUDIT_DETAILS_STRING_LENGTH,
                remainingCharacters,
            )

        if (
            value.length > allowedLength
        ) {
            state.truncated = true
        }

        const result =
            value.slice(
                0,
                allowedLength,
            )

        state.characters +=
            result.length

        return value.length > allowedLength
            ? `${result}…[TRUNCATED]`
            : result
    }

    if (typeof value === 'number') {
        if (!Number.isFinite(value)) {
            state.truncated = true
            return '[INVALID_NUMBER]'
        }

        if (
            Number.isInteger(value)
            && !Number.isSafeInteger(value)
        ) {
            state.truncated = true
            return '[UNSAFE_INTEGER]'
        }

        return value
    }

    if (Array.isArray(value)) {
        if (
            value.length
                > MAX_AUDIT_DETAILS_ARRAY_ITEMS
        ) {
            state.truncated = true
        }

        return value
            .slice(
                0,
                MAX_AUDIT_DETAILS_ARRAY_ITEMS,
            )
            .map(
                (item) =>
                    sanitizeJsonValue(
                        item,
                        depth + 1,
                        state,
                    ),
            )
    }

    if (!isRecord(value)) {
        state.truncated = true
        return '[UNSUPPORTED_VALUE]'
    }

    const result =
        Object.create(
            null,
        ) as Record<string, JsonValue>

    const entries =
        Object.entries(value)

    if (
        entries.length
            > MAX_AUDIT_DETAILS_KEYS
    ) {
        state.truncated = true
    }

    entries
        .slice(
            0,
            MAX_AUDIT_DETAILS_KEYS,
        )
        .forEach(
            (
                [originalKey, nested],
                index,
            ) => {
                const key =
                    sanitizeAuditDetailKey(
                        originalKey,
                        index,
                        state,
                        result,
                    )

                if (
                    isSensitiveAuditKey(
                        originalKey,
                    )
                ) {
                    result[key] =
                        '[REDACTED]'
                    return
                }

                result[key] =
                    sanitizeJsonValue(
                        nested,
                        depth + 1,
                        state,
                    )
            },
        )

    return result
}

function sanitizeAuditDetailKey(
    originalKey: string,
    index: number,
    state: {
        nodes: number
        characters: number
        truncated: boolean
    },
    current:
        Record<string, JsonValue>,
): string {
    const dangerousKey =
        originalKey === '__proto__'
        || originalKey === 'prototype'
        || originalKey === 'constructor'

    const remainingCharacters =
        Math.max(
            0,
            MAX_AUDIT_DETAILS_TOTAL_CHARACTERS
            - state.characters,
        )

    const allowedLength =
        Math.min(
            MAX_AUDIT_DETAILS_KEY_LENGTH,
            remainingCharacters,
        )

    let candidate =
        dangerousKey
            ? `[BLOCKED_KEY_${index}]`
            : originalKey.slice(
                0,
                allowedLength,
            )

    if (
        dangerousKey
        || originalKey.length
            > allowedLength
    ) {
        state.truncated = true
    }

    if (!candidate) {
        candidate =
            `[TRUNCATED_KEY_${index}]`
    }

    if (
        Object.prototype.hasOwnProperty.call(
            current,
            candidate,
        )
    ) {
        candidate =
            `${candidate}#${index}`
    }

    state.characters +=
        candidate.length

    return candidate
}

function isSensitiveAuditKey(
    key: string,
): boolean {
    const normalized = key
        .toLowerCase()
        .replace(/[^a-z0-9]/g, '')

    return SENSITIVE_AUDIT_KEYS.has(
        normalized,
    )
}

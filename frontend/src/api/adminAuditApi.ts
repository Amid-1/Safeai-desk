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
    expectInstant,
    expectNullableString,
    expectNullableUuid,
    expectRecord,
    expectString,
    expectUuid,
    isRecord,
    parsePageResponse,
} from './runtime'
import type { PageResponse } from '../utils/page'

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

        // HTTP-контракт AuditEventFilter использует userId/userEmail/organizationId.
        // Во frontend-модели сохраняем более точные actor/target-имена и маппим их здесь.
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

// ============================================================
// frontend/src/api/adminAudit/auditClient.ts
// ============================================================

import { API_TIMEOUTS, apiRequest } from '../http'
import { buildQueryString, normalizePage, normalizePageSize, uuidPathSegment } from '../query'
import { contractError, parsePageResponse } from '../runtime'
import type { PageResponse } from '../../utils/page'
import type { RequestOptions, AuditEventFilter, AuditEvent, AuditActorDirectoryItem, AuditTargetOrganizationDirectoryItem } from './auditTypes'
import { parseAuditEvent, parseAuditEventType, parseAuditActorDirectoryItem, parseAuditTargetOrganizationDirectoryItem } from './auditParsers'

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

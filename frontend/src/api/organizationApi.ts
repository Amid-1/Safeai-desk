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
    expectEnum,
    expectInstant,
    expectOptionalNonNegativeInteger,
    expectRecord,
    expectString,
    expectUuid,
    parsePageResponse,
} from './runtime'
import type { PageResponse } from '../utils/page'

const ORGANIZATION_TYPES = [
    'PLATFORM',
    'TENANT',
] as const

export type OrganizationType =
    | 'PLATFORM'
    | 'TENANT'
    | 'UNKNOWN'

export type Organization = {
    id: string
    name: string
    enabled: boolean

    // UNKNOWN/null поддерживают чтение старого DTO.
    // Mutations при неизвестной защите блокируются fail-closed.
    type: OrganizationType
    protected: boolean | null
    version: number | null

    createdAt: string
    updatedAt: string | null
}

export type OrganizationDirectoryItem = {
    id: string
    name: string
    enabled: boolean
    type: OrganizationType
    protected: boolean | null
}

export type OrganizationDisableImpact = {
    organizationId: string
    organizationVersion: number
    enabledUsers: number
    administrators: number
    activeRefreshSessions: number
    activeChatOperations: number
}

export type CreateOrganizationRequest = {
    name: string
}

export type UpdateOrganizationNameRequest = {
    name: string
    expectedVersion: number
}

export type DisableOrganizationRequest = {
    expectedVersion: number
    confirmationName: string
}

export type EnableOrganizationRequest = {
    expectedVersion: number
}

type RequestOptions = {
    signal?: AbortSignal
}

export async function getOrganizations(
    page = 0,
    size = 50,
    options: RequestOptions = {},
): Promise<PageResponse<Organization>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(
            size,
            50,
            200,
        ),
    })

    const response = await apiRequest<unknown>(
        `/api/organizations${query}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parsePageResponse(
        response,
        parseOrganization,
    )
}

export async function searchOrganizationDirectory(
    query: string,
    limit = 20,
    options: RequestOptions = {},
): Promise<OrganizationDirectoryItem[]> {
    const search = buildQueryString({
        query: query.trim(),
        limit: normalizePageSize(
            limit,
            20,
            50,
        ),
    })

    const response = await apiRequest<unknown>(
        `/api/organizations/directory${search}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    if (!Array.isArray(response)) {
        throw contractError(
            'organizationDirectory должен быть массивом',
        )
    }

    return response.map(
        (item, index) =>
            parseOrganizationDirectoryItem(
                item,
                `organizationDirectory[${index}]`,
            ),
    )
}

export async function createOrganization(
    request: CreateOrganizationRequest,
    options: RequestOptions = {},
): Promise<Organization> {
    const response = await apiRequest<unknown>(
        '/api/organizations',
        {
            method: 'POST',
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseOrganization(response)
}

export async function updateOrganizationName(
    organizationId: string,
    request: UpdateOrganizationNameRequest,
    options: RequestOptions = {},
): Promise<Organization> {
    const response = await apiRequest<unknown>(
        `/api/organizations/${uuidPathSegment(organizationId)}`,
        {
            method: 'PATCH',
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseOrganization(response)
}

export async function getOrganizationDisableImpact(
    organizationId: string,
    options: RequestOptions = {},
): Promise<OrganizationDisableImpact> {
    const response = await apiRequest<unknown>(
        `/api/organizations/${uuidPathSegment(organizationId)}/disable-impact`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseOrganizationDisableImpact(
        response,
    )
}

export async function disableOrganization(
    organizationId: string,
    request: DisableOrganizationRequest,
    options: RequestOptions = {},
): Promise<Organization> {
    const response = await apiRequest<unknown>(
        `/api/organizations/${uuidPathSegment(organizationId)}/disable`,
        {
            method: 'POST',
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseOrganization(response)
}

export async function enableOrganization(
    organizationId: string,
    request: EnableOrganizationRequest,
    options: RequestOptions = {},
): Promise<Organization> {
    const response = await apiRequest<unknown>(
        `/api/organizations/${uuidPathSegment(organizationId)}/enable`,
        {
            method: 'POST',
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseOrganization(response)
}

export function parseOrganization(
    value: unknown,
    field = 'organization',
): Organization {
    const record = expectRecord(value, field)

    const rawType = record.type
    const type = rawType === undefined
        ? 'UNKNOWN'
        : expectEnum(
            rawType,
            `${field}.type`,
            ORGANIZATION_TYPES,
        )

    const protectedValue =
        record.protected === undefined
            ? null
            : expectBoolean(
                record.protected,
                `${field}.protected`,
            )

    return {
        id: expectUuid(
            record.id,
            `${field}.id`,
        ),
        name: expectString(
            record.name,
            `${field}.name`,
            {
                maxLength: 255,
            },
        ),
        enabled: expectBoolean(
            record.enabled,
            `${field}.enabled`,
        ),
        type,
        protected: protectedValue,
        version:
            expectOptionalNonNegativeInteger(
                record.version,
                `${field}.version`,
            ),
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        updatedAt:
            record.updatedAt === undefined
            || record.updatedAt === null
                ? null
                : expectInstant(
                    record.updatedAt,
                    `${field}.updatedAt`,
                ),
    }
}

export function isOrganizationProtectionKnown(
    organization: Pick<
        Organization,
        'type' | 'protected'
    >,
): boolean {
    return organization.type !== 'UNKNOWN'
        && organization.protected !== null
}

export function isProtectedOrganization(
    organization: Pick<
        Organization,
        'type' | 'protected'
    >,
): boolean {
    // Fail-closed: неизвестный contract не даёт mutation-кнопок.
    return !isOrganizationProtectionKnown(
        organization,
    )
        || organization.protected === true
        || organization.type === 'PLATFORM'
}

export function normalizeOrganizationName(
    value: string,
): string {
    return value
        .trim()
        .replace(/\s+/g, ' ')
}

function parseOrganizationDirectoryItem(
    value: unknown,
    field: string,
): OrganizationDirectoryItem {
    const record = expectRecord(value, field)
    const parsed = parseOrganization(
        {
            ...record,
            createdAt:
                record.createdAt
                ?? '1970-01-01T00:00:00Z',
        },
        field,
    )

    return {
        id: parsed.id,
        name: parsed.name,
        enabled: parsed.enabled,
        type: parsed.type,
        protected: parsed.protected,
    }
}

function parseOrganizationDisableImpact(
    value: unknown,
): OrganizationDisableImpact {
    const record = expectRecord(
        value,
        'organizationDisableImpact',
    )

    return {
        organizationId: expectUuid(
            record.organizationId,
            'organizationDisableImpact.organizationId',
        ),
        organizationVersion:
            parseCount(
                record.organizationVersion,
                'organizationDisableImpact.organizationVersion',
            ),
        enabledUsers: parseCount(
            record.enabledUsers,
            'organizationDisableImpact.enabledUsers',
        ),
        administrators: parseCount(
            record.administrators,
            'organizationDisableImpact.administrators',
        ),
        activeRefreshSessions: parseCount(
            record.activeRefreshSessions,
            'organizationDisableImpact.activeRefreshSessions',
        ),
        activeChatOperations: parseCount(
            record.activeChatOperations,
            'organizationDisableImpact.activeChatOperations',
        ),
    }
}

function parseCount(
    value: unknown,
    field: string,
): number {
    if (
        typeof value !== 'number'
        || !Number.isInteger(value)
        || value < 0
    ) {
        throw contractError(
            `${field} должен быть неотрицательным целым числом`,
        )
    }

    return value
}

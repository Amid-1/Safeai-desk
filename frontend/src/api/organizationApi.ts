// ============================================================
// frontend/src/api/organizationApi.ts
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
    expectEnum,
    expectInstant,
    expectNonNegativeInteger,
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

const ORGANIZATION_CONFIRMATION_QUOTES =
    /["'«»„“”‘’‚‛‹›`´]+/g

export type OrganizationType =
    typeof ORGANIZATION_TYPES[number]

export type Organization = {
    id: string
    name: string
    enabled: boolean
    type: OrganizationType
    protected: boolean
    version: number
    createdAt: string
    updatedAt: string
}

export type OrganizationDirectoryItem = {
    id: string
    name: string
    enabled: boolean
    type: OrganizationType
    protected: boolean
    version: number
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

type ParsedOrganizationCore = {
    id: string
    name: string
    enabled: boolean
    type: OrganizationType
    protected: boolean
    version: number
}

function organizationPath(
    organizationId: string,
    suffix = '',
): string {
    return (
        `/api/organizations/${uuidPathSegment(organizationId)}`
        + suffix
    )
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
            100,
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

export async function getOrganizationDetails(
    organizationId: string,
    options: RequestOptions = {},
): Promise<Organization> {
    const response = await apiRequest<unknown>(
        organizationPath(organizationId),
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseOrganization(
        response,
        'organizationDetails',
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
        organizationPath(organizationId),
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
        organizationPath(
            organizationId,
            '/disable-impact',
        ),
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
        organizationPath(
            organizationId,
            '/disable',
        ),
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
        organizationPath(
            organizationId,
            '/enable',
        ),
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
    const core = parseOrganizationCore(
        record,
        field,
    )

    return {
        ...core,
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        updatedAt: expectInstant(
            record.updatedAt,
            `${field}.updatedAt`,
        ),
    }
}

export function parseOrganizationDirectoryItem(
    value: unknown,
    field = 'organizationDirectoryItem',
): OrganizationDirectoryItem {
    const record = expectRecord(value, field)

    return parseOrganizationCore(
        record,
        field,
    )
}

export function isProtectedOrganization(
    organization: Pick<
        Organization,
        'type' | 'protected'
    >,
): boolean {
    return organization.protected
        || organization.type === 'PLATFORM'
}

export function normalizeOrganizationName(
    value: string,
): string {
    return value
        .trim()
        .replace(/\s+/g, ' ')
}

/**
 * Нормализация только для typed confirmation опасной операции.
 *
 * Не используется для сохранения/переименования организации:
 * уникальность имени на backend имеет отдельную семантику.
 */
export function normalizeOrganizationConfirmation(
    value: string,
): string {
    return normalizeOrganizationName(
        value
            .normalize('NFKC')
            .replace(
                ORGANIZATION_CONFIRMATION_QUOTES,
                ' ',
            ),
    ).toLowerCase()
}

function parseOrganizationCore(
    record: Record<string, unknown>,
    field: string,
): ParsedOrganizationCore {
    const type = expectEnum(
        record.type,
        `${field}.type`,
        ORGANIZATION_TYPES,
    )

    const protectedOrganization =
        expectBoolean(
            record.protected,
            `${field}.protected`,
        )

    validateProtectionContract(
        type,
        protectedOrganization,
        field,
    )

    const name = expectString(
        record.name,
        `${field}.name`,
        {
            maxLength: 255,
        },
    )

    if (
        normalizeOrganizationName(name)
        !== name
    ) {
        throw contractError(
            `${field}.name не канонизирован`,
        )
    }

    return {
        id: expectUuid(
            record.id,
            `${field}.id`,
        ),
        name,
        enabled: expectBoolean(
            record.enabled,
            `${field}.enabled`,
        ),
        type,
        protected: protectedOrganization,
        version: expectNonNegativeInteger(
            record.version,
            `${field}.version`,
        ),
    }
}

function validateProtectionContract(
    type: OrganizationType,
    protectedOrganization: boolean,
    field: string,
): void {
    if (
        type === 'PLATFORM'
        && !protectedOrganization
    ) {
        throw contractError(
            `${field}: PLATFORM должна быть protected`,
        )
    }

    if (
        type === 'TENANT'
        && protectedOrganization
    ) {
        throw contractError(
            `${field}: TENANT не может быть protected`,
        )
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
            expectNonNegativeInteger(
                record.organizationVersion,
                'organizationDisableImpact.organizationVersion',
            ),
        enabledUsers:
            expectNonNegativeInteger(
                record.enabledUsers,
                'organizationDisableImpact.enabledUsers',
            ),
        administrators:
            expectNonNegativeInteger(
                record.administrators,
                'organizationDisableImpact.administrators',
            ),
        activeRefreshSessions:
            expectNonNegativeInteger(
                record.activeRefreshSessions,
                'organizationDisableImpact.activeRefreshSessions',
            ),
        activeChatOperations:
            expectNonNegativeInteger(
                record.activeChatOperations,
                'organizationDisableImpact.activeChatOperations',
            ),
    }
}

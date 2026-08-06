// ============================================================
// frontend/src/api/userApi.ts
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
import type { UserRole } from './types'
import {
    contractError,
    expectBoolean,
    expectInstant,
    expectNullableInstant,
    expectNullableString,
    expectOptionalNonNegativeInteger,
    expectRecord,
    expectString,
    expectStringArray,
    expectUuid,
    parsePageResponse,
} from './runtime'
import type { PageResponse } from '../utils/page'

const USER_ROLES: readonly UserRole[] = [
    'SUPER_ADMIN',
    'ADMIN',
    'USER',
]

export type User = {
    id: string
    organizationId: string
    email: string
    fullName: string | null
    enabled: boolean
    roles: [UserRole]
    version: number
    createdAt: string
    updatedAt: string
    lastLoginAt: string | null
}

export type UserDetails = User

export type UserStatistics = {
    total: number
    administrators: number
    users: number
    enabled: number
    disabled: number
}

export type UserListRoleFilter =
    | 'ADMIN'
    | 'USER'

export type CreateUserRequest = {
    organizationId: string
    email: string
    password: string
    fullName: string | null

    /**
     * Wire contract remains an array for backward compatibility,
     * but both frontend policy and backend require exactly one role.
     */
    roles: [Exclude<UserRole, 'SUPER_ADMIN'>]
}

export type UpdateUserRequest = {
    email: string
    fullName: string | null
    expectedVersion: number
}

export type UpdateUserEnabledRequest = {
    enabled: boolean
    expectedVersion: number
}

export type UpdateUserRolesRequest = {
    roles: [Exclude<UserRole, 'SUPER_ADMIN'>]
    expectedVersion: number
}

export type ResetUserPasswordRequest = {
    password: string
    expectedVersion: number
}

export type PermanentDeleteUserRequest = {
    confirmationEmail: string
    expectedVersion: number
}

type RequestOptions = {
    signal?: AbortSignal
}

function userPath(
    userId: string,
    suffix = '',
): string {
    return (
        `/api/users/${uuidPathSegment(userId)}`
        + suffix
    )
}

export async function getUsers(
    page = 0,
    size = 50,
    role?: UserListRoleFilter,
    options: RequestOptions = {},
): Promise<PageResponse<User>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(
            size,
            50,
            200,
        ),
        role,
    })

    const response = await apiRequest<unknown>(
        `/api/users${query}`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parsePageResponse(
        response,
        parseUser,
    )
}

export async function getUserDetails(
    userId: string,
    options: RequestOptions = {},
): Promise<UserDetails> {
    const response = await apiRequest<unknown>(
        userPath(userId),
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseUser(response)
}

export async function getUserStatistics(
    options: RequestOptions = {},
): Promise<UserStatistics> {
    const response = await apiRequest<unknown>(
        '/api/users/statistics',
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseUserStatistics(response)
}

export async function createUser(
    request: CreateUserRequest,
    options: RequestOptions = {},
): Promise<User> {
    const response = await apiRequest<unknown>(
        '/api/users',
        {
            method: 'POST',
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseUser(response)
}

export async function updateUser(
    userId: string,
    request: UpdateUserRequest,
    options: RequestOptions = {},
): Promise<User> {
    return sendUserMutation(
        userPath(userId),
        'PATCH',
        request,
        options,
    )
}

export async function updateUserEnabled(
    userId: string,
    request: UpdateUserEnabledRequest,
    options: RequestOptions = {},
): Promise<User> {
    return sendUserMutation(
        userPath(userId, '/enabled'),
        'PATCH',
        request,
        options,
    )
}

export async function updateUserRoles(
    userId: string,
    request: UpdateUserRolesRequest,
    options: RequestOptions = {},
): Promise<User> {
    return sendUserMutation(
        userPath(userId, '/roles'),
        'PATCH',
        request,
        options,
    )
}

export function resetUserPassword(
    userId: string,
    request: ResetUserPasswordRequest,
    options: RequestOptions = {},
): Promise<void> {
    return apiRequest<void>(
        userPath(userId, '/reset-password'),
        {
            method: 'POST',
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )
}

export function permanentlyDeleteUser(
    userId: string,
    request: PermanentDeleteUserRequest,
    options: RequestOptions = {},
): Promise<void> {
    return apiRequest<void>(
        userPath(
            userId,
            '/permanent-deletion',
        ),
        {
            method: 'POST',
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )
}

export function parseUser(
    value: unknown,
    field = 'user',
): User {
    const record = expectRecord(value, field)

    const email = expectString(
        record.email,
        `${field}.email`,
        {
            maxLength: 255,
        },
    )

    if (
        email !== email.trim()
        || email !== email.toLowerCase()
        || !email.includes('@')
    ) {
        throw contractError(
            `${field}.email не канонизирован`,
        )
    }

    const roles = expectStringArray(
        record.roles,
        `${field}.roles`,
        USER_ROLES,
    )

    if (roles.length !== 1) {
        throw contractError(
            `${field}.roles должен содержать ровно одну системную роль`,
        )
    }

    const singleRole = roles as [UserRole]

    return {
        id: expectUuid(
            record.id,
            `${field}.id`,
        ),
        organizationId: expectUuid(
            record.organizationId,
            `${field}.organizationId`,
        ),
        email,
        fullName: expectNullableString(
            record.fullName ?? null,
            `${field}.fullName`,
            {
                maxLength: 255,
            },
        ),
        enabled: expectBoolean(
            record.enabled,
            `${field}.enabled`,
        ),
        roles: singleRole,
        version: parseRequiredVersion(
            record.version,
            `${field}.version`,
        ),
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        updatedAt: expectInstant(
            record.updatedAt,
            `${field}.updatedAt`,
        ),
        lastLoginAt: expectNullableInstant(
            record.lastLoginAt ?? null,
            `${field}.lastLoginAt`,
        ),
    }
}

export function parseUserStatistics(
    value: unknown,
    field = 'userStatistics',
): UserStatistics {
    const record = expectRecord(value, field)

    const total = parseCount(
        record.total,
        `${field}.total`,
    )
    const administrators = parseCount(
        record.administrators,
        `${field}.administrators`,
    )
    const users = parseCount(
        record.users,
        `${field}.users`,
    )
    const enabled = parseCount(
        record.enabled,
        `${field}.enabled`,
    )
    const disabled = parseCount(
        record.disabled,
        `${field}.disabled`,
    )

    if (
        administrators > total
        || users > total
        || enabled + disabled !== total
    ) {
        throw contractError(
            `${field} содержит несогласованные значения`,
        )
    }

    return {
        total,
        administrators,
        users,
        enabled,
        disabled,
    }
}

async function sendUserMutation<
    TRequest extends object,
>(
    path: string,
    method: 'POST' | 'PATCH',
    request: TRequest,
    options: RequestOptions,
): Promise<User> {
    const response = await apiRequest<unknown>(
        path,
        {
            method,
            json: request,
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseUser(response)
}

function parseRequiredVersion(
    value: unknown,
    field: string,
): number {
    const version =
        expectOptionalNonNegativeInteger(
            value,
            field,
        )

    if (version === null) {
        throw contractError(
            `${field} обязателен`,
        )
    }

    return version
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
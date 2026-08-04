import {
    ApiError,
    API_TIMEOUTS,
    apiRequest,
    ensureCsrfToken,
    rotateCsrfToken,
} from './http'
import type { UserRole } from './types'

const AUTH_BASE_PATH = '/api/auth'

const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const EMAIL_PATTERN =
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const USER_ROLES = new Set<UserRole>([
    'SUPER_ADMIN',
    'ADMIN',
    'USER',
])

export type LoginRequest = {
    email: string
    password: string
}

export type AuthUser = {
    id: string
    organizationId: string
    email: string
    fullName: string | null
    enabled: true
    roles: UserRole[]
}

type AuthRequestOptions = {
    signal?: AbortSignal
}

export async function login(
    request: LoginRequest,
    options: AuthRequestOptions = {},
): Promise<AuthUser> {
    const email = request.email
        .trim()
        .toLowerCase()

    const csrfTokenBeforeLogin =
        await ensureCsrfToken()

    const response = await apiRequest<unknown>(
        `${AUTH_BASE_PATH}/login`,
        {
            method: 'POST',
            json: {
                email,
                password: request.password,
            },
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.auth,
        },
    )

    const user = parseAuthUser(response)

    // Backend должен выдать новый CSRF token для
    // уже аутентифицированной сессии.
    await rotateCsrfToken(
        csrfTokenBeforeLogin,
    )

    return user
}

export async function getCurrentUser(
    options: AuthRequestOptions = {},
): Promise<AuthUser> {
    const response = await apiRequest<unknown>(
        `${AUTH_BASE_PATH}/me`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.auth,
        },
    )

    return parseAuthUser(response)
}

export function logout(
    options: AuthRequestOptions = {},
): Promise<void> {
    return apiRequest<void>(
        `${AUTH_BASE_PATH}/logout`,
        {
            method: 'POST',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.auth,
        },
    )
}

export function parseAuthUser(
    value: unknown,
): AuthUser {
    if (!isRecord(value)) {
        throw invalidAuthResponse()
    }

    const {
        id,
        organizationId,
        email,
        fullName,
        enabled,
        roles,
    } = value

    if (
        typeof id !== 'string'
        || !UUID_PATTERN.test(id)
        || typeof organizationId !== 'string'
        || !UUID_PATTERN.test(organizationId)
        || typeof email !== 'string'
        || !EMAIL_PATTERN.test(email)
        || email !== email.trim().toLowerCase()
        || (
            fullName !== null
            && typeof fullName !== 'string'
        )
        || enabled !== true
        || !Array.isArray(roles)
        || roles.length === 0
    ) {
        throw invalidAuthResponse()
    }

    const normalizedRoles: UserRole[] = []

    for (const role of roles) {
        if (
            typeof role !== 'string'
            || !USER_ROLES.has(role as UserRole)
        ) {
            throw invalidAuthResponse()
        }

        const typedRole = role as UserRole

        if (!normalizedRoles.includes(typedRole)) {
            normalizedRoles.push(typedRole)
        }
    }

    return {
        id: id.toLowerCase(),
        organizationId:
            organizationId.toLowerCase(),
        email,
        fullName,
        enabled: true,
        roles: normalizedRoles,
    }
}

function invalidAuthResponse(): ApiError {
    return new ApiError(
        'Сервер вернул некорректные данные пользователя',
        {
            status: 0,
            error: 'INVALID_AUTH_RESPONSE',
            message:
                'Сервер вернул некорректные данные пользователя',
        },
        0,
    )
}

function isRecord(
    value: unknown,
): value is Record<string, unknown> {
    return typeof value === 'object'
        && value !== null
        && !Array.isArray(value)
}

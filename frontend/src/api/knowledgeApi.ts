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
    expectNullableString,
    expectRecord,
    expectString,
    expectUuid,
    parsePageResponse,
} from './runtime'

import type {
    PageResponse,
} from '../utils/page'

const BASE_PATH =
    '/api/knowledge-bases'

const VISIBILITIES = [
    'ORGANIZATION',
    'MEMBERS',
] as const

const ACCESS_LEVELS = [
    'VIEWER',
    'EDITOR',
    'OWNER',
] as const

export type KnowledgeBaseVisibility =
    typeof VISIBILITIES[number]

export type KnowledgeBaseAccessLevel =
    typeof ACCESS_LEVELS[number]

export type KnowledgeBase = {
    id: string
    organizationId: string

    name: string
    description: string | null

    visibility:
        KnowledgeBaseVisibility

    enabled: boolean

    createdByUserId: string

    version: number

    createdAt: string
    updatedAt: string
}

export type KnowledgeBaseMember = {
    knowledgeBaseId: string
    userId: string

    email: string
    fullName: string | null

    accessLevel:
        KnowledgeBaseAccessLevel

    version: number

    createdAt: string
    updatedAt: string
}

export type KnowledgeMemberCandidate = {
    userId: string
    email: string
    fullName: string | null
}

export type CreateKnowledgeBaseRequest = {
    name: string
    description: string | null

    visibility:
        KnowledgeBaseVisibility
}

export type UpdateKnowledgeBaseRequest = {
    name: string
    description: string | null

    visibility:
        KnowledgeBaseVisibility

    enabled: boolean
    expectedVersion: number
}

export type CreateKnowledgeBaseMemberRequest = {
    userId: string

    accessLevel:
        KnowledgeBaseAccessLevel
}

export type UpdateKnowledgeBaseMemberRequest = {
    accessLevel:
        KnowledgeBaseAccessLevel

    expectedVersion: number
}

type RequestOptions = {
    signal?: AbortSignal
}

/*
 * Возвращает доступные текущему пользователю
 * Knowledge Bases.
 *
 * Tenant/resource authorization является
 * обязанностью backend.
 */
export async function getKnowledgeBases(
    page = 0,
    size = 50,
    options: RequestOptions = {},
): Promise<
    PageResponse<KnowledgeBase>
> {
    const response =
        await apiRequest<unknown>(
            BASE_PATH
            + buildQueryString({
                page:
                    normalizePage(
                        page,
                    ),

                size:
                    normalizePageSize(
                        size,
                        50,
                        100,
                    ),
            }),
            {
                method: 'GET',

                signal:
                    options.signal,

                timeoutMs:
                    API_TIMEOUTS.default,
            },
        )

    return parsePageResponse(
        response,
        parseKnowledgeBase,
    )
}

export async function getKnowledgeBase(knowledgeBaseId:string,options:RequestOptions={}):Promise<KnowledgeBase>{
    const response=await apiRequest<unknown>(`${BASE_PATH}/${uuidPathSegment(knowledgeBaseId)}`,{method:'GET',signal:options.signal,timeoutMs:API_TIMEOUTS.default})
    return parseKnowledgeBase(response,'knowledgeBase')
}

export async function createKnowledgeBase(
    request:
        CreateKnowledgeBaseRequest,
    options: RequestOptions = {},
): Promise<KnowledgeBase> {
    const response =
        await apiRequest<unknown>(
            BASE_PATH,
            {
                method: 'POST',

                json: {
                    name:
                        request.name,

                    description:
                        request.description,

                    visibility:
                        request.visibility,
                },

                signal:
                    options.signal,

                timeoutMs:
                    API_TIMEOUTS.default,
            },
        )

    return parseKnowledgeBase(
        response,
        'knowledgeBase',
    )
}

export async function updateKnowledgeBase(
    knowledgeBaseId: string,
    request:
        UpdateKnowledgeBaseRequest,
    options: RequestOptions = {},
): Promise<KnowledgeBase> {
    const response =
        await apiRequest<unknown>(
            `${
                BASE_PATH
            }/${
                uuidPathSegment(
                    knowledgeBaseId,
                )
            }`,
            {
                method: 'PATCH',

                json: {
                    name:
                        request.name,

                    description:
                        request.description,

                    visibility:
                        request.visibility,

                    enabled:
                        request.enabled,

                    expectedVersion:
                        request.expectedVersion,
                },

                signal:
                    options.signal,

                timeoutMs:
                    API_TIMEOUTS.default,
            },
        )

    return parseKnowledgeBase(
        response,
        'knowledgeBase',
    )
}

export async function getKnowledgeBaseMembers(
    knowledgeBaseId: string,
    page = 0,
    size = 50,
    options: RequestOptions = {},
): Promise<
    PageResponse<KnowledgeBaseMember>
> {
    const response =
        await apiRequest<unknown>(
            `${
                BASE_PATH
            }/${
                uuidPathSegment(
                    knowledgeBaseId,
                )
            }/members`
            + buildQueryString({
                page:
                    normalizePage(
                        page,
                    ),

                size:
                    normalizePageSize(
                        size,
                        50,
                        100,
                    ),
            }),
            {
                method: 'GET',

                signal:
                    options.signal,

                timeoutMs:
                    API_TIMEOUTS.default,
            },
        )

    return parsePageResponse(
        response,
        parseKnowledgeBaseMember,
    )
}

export async function searchKnowledgeMemberCandidates(
    query: string,
    limit = 20,
    options: RequestOptions = {},
): Promise<
    KnowledgeMemberCandidate[]
> {
    const safeLimit =
        normalizeCandidateLimit(
            limit,
        )

    const response =
        await apiRequest<unknown>(
            `${BASE_PATH}/member-candidates`
            + buildQueryString({
                query:
                    query.trim(),

                limit:
                    safeLimit,
            }),
            {
                method: 'GET',

                signal:
                    options.signal,

                timeoutMs:
                    API_TIMEOUTS.default,
            },
        )

    if (
        !Array.isArray(
            response,
        )
    ) {
        throw contractError(
            'Сервер вернул некорректный список кандидатов Knowledge Base.',
        )
    }

    return response.map(
        (
            item,
            index,
        ) =>
            parseKnowledgeMemberCandidate(
                item,
                `candidates[${index}]`,
            ),
    )
}

export async function addKnowledgeBaseMember(
    knowledgeBaseId: string,
    request:
        CreateKnowledgeBaseMemberRequest,
    options: RequestOptions = {},
): Promise<KnowledgeBaseMember> {
    const response =
        await apiRequest<unknown>(
            `${
                BASE_PATH
            }/${
                uuidPathSegment(
                    knowledgeBaseId,
                )
            }/members`,
            {
                method: 'POST',

                json: {
                    userId:
                        uuidPathSegment(
                            request.userId,
                        ),

                    accessLevel:
                        request.accessLevel,
                },

                signal:
                    options.signal,

                timeoutMs:
                    API_TIMEOUTS.default,
            },
        )

    return parseKnowledgeBaseMember(
        response,
        'member',
    )
}

export async function updateKnowledgeBaseMember(
    knowledgeBaseId: string,
    userId: string,
    request:
        UpdateKnowledgeBaseMemberRequest,
    options: RequestOptions = {},
): Promise<KnowledgeBaseMember> {
    const response =
        await apiRequest<unknown>(
            `${
                BASE_PATH
            }/${
                uuidPathSegment(
                    knowledgeBaseId,
                )
            }/members/${
                uuidPathSegment(
                    userId,
                )
            }`,
            {
                method: 'PATCH',

                json: {
                    accessLevel:
                        request.accessLevel,

                    expectedVersion:
                        request.expectedVersion,
                },

                signal:
                    options.signal,

                timeoutMs:
                    API_TIMEOUTS.default,
            },
        )

    return parseKnowledgeBaseMember(
        response,
        'member',
    )
}

export function removeKnowledgeBaseMember(
    knowledgeBaseId: string,
    userId: string,
    expectedVersion: number,
    options: RequestOptions = {},
): Promise<void> {
    assertExpectedVersion(
        expectedVersion,
    )

    return apiRequest<void>(
        `${
            BASE_PATH
        }/${
            uuidPathSegment(
                knowledgeBaseId,
            )
        }/members/${
            uuidPathSegment(
                userId,
            )
        }`
        + buildQueryString({
            expectedVersion,
        }),
        {
            method: 'DELETE',

            signal:
                options.signal,

            timeoutMs:
                API_TIMEOUTS.default,
        },
    )
}

export function parseKnowledgeBase(
    value: unknown,
    field = 'knowledgeBase',
): KnowledgeBase {
    const record =
        expectRecord(
            value,
            field,
        )

    const description =
        record.description
            === undefined
            ? null
            : expectNullableString(
                record.description,
                `${field}.description`,
                {
                    maxLength:
                        2_000,
                },
            )

    return {
        id:
            expectUuid(
                record.id,
                `${field}.id`,
            ),

        organizationId:
            expectUuid(
                record.organizationId,
                `${field}.organizationId`,
            ),

        name:
            expectString(
                record.name,
                `${field}.name`,
                {
                    maxLength:
                        255,
                },
            ),

        description,

        visibility:
            expectEnum(
                record.visibility,
                `${field}.visibility`,
                VISIBILITIES,
            ),

        enabled:
            expectBoolean(
                record.enabled,
                `${field}.enabled`,
            ),

        createdByUserId:
            expectUuid(
                record.createdByUserId,
                `${field}.createdByUserId`,
            ),

        version:
            expectNonNegativeInteger(
                record.version,
                `${field}.version`,
            ),

        createdAt:
            expectInstant(
                record.createdAt,
                `${field}.createdAt`,
            ),

        updatedAt:
            expectInstant(
                record.updatedAt,
                `${field}.updatedAt`,
            ),
    }
}

export function parseKnowledgeBaseMember(
    value: unknown,
    field = 'member',
): KnowledgeBaseMember {
    const record =
        expectRecord(
            value,
            field,
        )

    const fullName =
        record.fullName
            === undefined
            ? null
            : expectNullableString(
                record.fullName,
                `${field}.fullName`,
                {
                    maxLength:
                        255,
                },
            )

    return {
        knowledgeBaseId:
            expectUuid(
                record.knowledgeBaseId,
                `${field}.knowledgeBaseId`,
            ),

        userId:
            expectUuid(
                record.userId,
                `${field}.userId`,
            ),

        email:
            expectString(
                record.email,
                `${field}.email`,
                {
                    maxLength:
                        255,
                },
            ),

        fullName,

        accessLevel:
            expectEnum(
                record.accessLevel,
                `${field}.accessLevel`,
                ACCESS_LEVELS,
            ),

        version:
            expectNonNegativeInteger(
                record.version,
                `${field}.version`,
            ),

        createdAt:
            expectInstant(
                record.createdAt,
                `${field}.createdAt`,
            ),

        updatedAt:
            expectInstant(
                record.updatedAt,
                `${field}.updatedAt`,
            ),
    }
}

function parseKnowledgeMemberCandidate(
    value: unknown,
    field: string,
): KnowledgeMemberCandidate {
    const record =
        expectRecord(
            value,
            field,
        )

    return {
        userId:
            expectUuid(
                record.userId,
                `${field}.userId`,
            ),

        email:
            expectString(
                record.email,
                `${field}.email`,
                {
                    maxLength:
                        255,
                },
            ),

        fullName:
            record.fullName
                === undefined
                ? null
                : expectNullableString(
                    record.fullName,
                    `${field}.fullName`,
                    {
                        maxLength:
                            255,
                    },
                ),
    }
}

function normalizeCandidateLimit(
    value: number,
): number {
    if (
        !Number.isFinite(
            value,
        )
    ) {
        return 20
    }

    return Math.min(
        Math.max(
            1,
            Math.trunc(
                value,
            ),
        ),
        50,
    )
}

function assertExpectedVersion(
    expectedVersion: number,
): void {
    if (
        !Number.isSafeInteger(
            expectedVersion,
        )
        || expectedVersion < 0
    ) {
        throw new Error(
            'expectedVersion должен быть неотрицательным целым числом.',
        )
    }
}

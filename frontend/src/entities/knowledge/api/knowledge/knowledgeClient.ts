// frontend/src/entities/knowledge/api/knowledge/knowledgeClient.ts
// ============================================================
// frontend/src/entities/knowledge/api/knowledge/knowledgeClient.ts
// ============================================================

import { API_TIMEOUTS, apiRequest } from '../../../../shared/api/http'
import { buildQueryString, normalizePage, normalizePageSize, uuidPathSegment } from '../../../../shared/api/query'
import { contractError, parsePageResponse } from '../../../../shared/api/runtime'
import type { PageResponse } from '../../../../shared/lib/page'
import type { RequestOptions, KnowledgeBase, KnowledgeBaseAccess, KnowledgeBaseMember, KnowledgeMemberCandidate, CreateKnowledgeBaseRequest, UpdateKnowledgeBaseRequest, CreateKnowledgeBaseMemberRequest, UpdateKnowledgeBaseMemberRequest, KnowledgeBaseListFilters } from './knowledgeTypes'
import { parseKnowledgeBase, parseKnowledgeBaseAccess, parseKnowledgeBaseMember, parseKnowledgeMemberCandidate } from './knowledgeParsers'
import { normalizeCandidateLimit, assertExpectedVersion } from './knowledgeValidation'

const BASE_PATH =
    '/api/knowledge-bases'

export async function searchKnowledgeBases(
    page: number,
    size: number,
    filters: KnowledgeBaseListFilters,
    options: RequestOptions = {},
): Promise<PageResponse<KnowledgeBase>> {
    const response = await apiRequest<unknown>(
        `${BASE_PATH}/search` + buildQueryString({
            page: normalizePage(page),
            size: normalizePageSize(size, 12, 100),
            query: filters.query?.trim() || undefined,
            enabled: filters.enabled,
            visibility: filters.visibility,
        }),
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parsePageResponse(response, parseKnowledgeBase)
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

export async function getKnowledgeBaseAccess(
    knowledgeBaseId: string,
    options: RequestOptions = {},
): Promise<KnowledgeBaseAccess> {
    const response = await apiRequest<unknown>(
        `${BASE_PATH}/${uuidPathSegment(knowledgeBaseId)}/access`,
        {
            method: 'GET',
            signal: options.signal,
            timeoutMs: API_TIMEOUTS.default,
        },
    )

    return parseKnowledgeBaseAccess(
        response,
        'knowledgeBaseAccess',
    )
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
    // Проверяем optimistic locking до сетевого запроса.
    assertExpectedVersion(request.expectedVersion)

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
    // Проверяем optimistic locking до сетевого запроса.
    assertExpectedVersion(request.expectedVersion)

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

// frontend/src/api/model/modelClient.ts
// Назначение: сетевые вызовы API моделей без бизнес-логики интерфейса.

import { apiRequest } from '../http'
import type {
    RuntimeModelStatus,
    RuntimeModelProbe,
    ModelCatalogEntry,
    OrganizationModelPolicy,
    ModelRouteDecision,
    ModelPolicyPreview,
    CreateModelCatalogVersionRequest,
    CreateOrganizationModelPolicyVersionRequest,
} from './modelTypes'
import {
    parseRuntimeModelStatus,
    parseRuntimeModelProbe,
    parseModelCatalogEntry,
    parseOrganizationModelPolicy,
    parseModelRouteDecision,
    parseModelPolicyPreview,
} from './modelParsers'
import { requireArray, requireUuid } from './modelWire'

type ModelApiRequestOptions = {
    signal?: AbortSignal
}

export async function getRuntimeModelStatus(
    signal?: AbortSignal,
): Promise<RuntimeModelStatus> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/runtime',
        {
            method: 'GET',
            signal,
        },
    )

    return parseRuntimeModelStatus(raw)
}

export async function probeRuntimeModel(
    options: ModelApiRequestOptions = {},
): Promise<RuntimeModelProbe> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/runtime/probe',
        {
            method: 'POST',
            signal: options.signal,
        },
    )

    return parseRuntimeModelProbe(raw)
}

export async function getModelCatalog(
    options: ModelApiRequestOptions = {},
): Promise<ModelCatalogEntry[]> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/catalog',
        {
            method: 'GET',
            signal: options.signal,
        },
    )

    return requireArray(raw, 'modelCatalog')
        .map((value, index) =>
            parseModelCatalogEntry(
                value,
                `modelCatalog[${index}]`,
            ),
        )
}

export async function getEffectiveModelCatalog(
    options: ModelApiRequestOptions = {},
): Promise<ModelCatalogEntry[]> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/catalog/effective',
        {
            method: 'GET',
            signal: options.signal,
        },
    )

    return requireArray(
        raw,
        'effectiveModelCatalog',
    ).map((value, index) =>
        parseModelCatalogEntry(
            value,
            `effectiveModelCatalog[${index}]`,
        ),
    )
}

export async function createModelCatalogVersion(
    request: CreateModelCatalogVersionRequest,
    options: ModelApiRequestOptions = {},
): Promise<ModelCatalogEntry> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/catalog',
        {
            method: 'POST',
            json: request,
            signal: options.signal,
        },
    )

    return parseModelCatalogEntry(
        raw,
        'modelCatalog',
    )
}

export async function importRuntimeModelCatalog(
    options: ModelApiRequestOptions = {},
): Promise<ModelCatalogEntry> {
    const raw = await apiRequest<unknown>(
        '/api/admin/models/catalog/import-runtime',
        {
            method: 'POST',
            signal: options.signal,
        },
    )

    return parseModelCatalogEntry(
        raw,
        'modelCatalog',
    )
}

export async function getOrganizationModelPolicy(
    organizationId: string,
    options: ModelApiRequestOptions = {},
): Promise<OrganizationModelPolicy> {
    const validatedOrganizationId =
        requireUuid(organizationId, 'organizationId')
    const raw = await apiRequest<unknown>(
        `/api/admin/models/policies/${
            encodeURIComponent(validatedOrganizationId)
        }`,
        {
            method: 'GET',
            signal: options.signal,
        },
    )

    return parseOrganizationModelPolicy(
        raw,
        'modelPolicy',
    )
}

export async function createOrganizationModelPolicyVersion(
    organizationId: string,
    request: CreateOrganizationModelPolicyVersionRequest,
    options: ModelApiRequestOptions = {},
): Promise<OrganizationModelPolicy> {
    const validatedOrganizationId =
        requireUuid(organizationId, 'organizationId')
    const raw = await apiRequest<unknown>(
        `/api/admin/models/policies/${
            encodeURIComponent(validatedOrganizationId)
        }`,
        {
            method: 'POST',
            json: request,
            signal: options.signal,
        },
    )

    return parseOrganizationModelPolicy(
        raw,
        'modelPolicy',
    )
}

export async function previewOrganizationModelPolicy(
    organizationId: string,
    request: CreateOrganizationModelPolicyVersionRequest,
    options: ModelApiRequestOptions = {},
): Promise<ModelPolicyPreview> {
    const validatedOrganizationId =
        requireUuid(organizationId, 'organizationId')
    const raw = await apiRequest<unknown>(
        `/api/admin/models/policies/${
            encodeURIComponent(validatedOrganizationId)
        }/preview`,
        {
            method: 'POST',
            json: request,
            signal: options.signal,
        },
    )

    return parseModelPolicyPreview(
        raw,
        'modelPolicyPreview',
    )
}

export async function getModelRouteDecision(
    decisionId: string,
    options: ModelApiRequestOptions = {},
): Promise<ModelRouteDecision> {
    const validatedDecisionId =
        requireUuid(decisionId, 'decisionId')
    const raw = await apiRequest<unknown>(
        `/api/admin/models/route-decisions/${
            encodeURIComponent(validatedDecisionId)
        }`,
        {
            method: 'GET',
            signal: options.signal,
        },
    )

    return parseModelRouteDecision(
        raw,
        'modelRouteDecision',
    )
}

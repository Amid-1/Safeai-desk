// ============================================================
// frontend/src/api/organizationApi.ts
// ============================================================

import { apiRequest } from './http'
import { buildQueryString, normalizePage, normalizePageSize, pathSegment } from './query'
import type { PageResponse } from '../utils/page'

export type Organization = {
    id: string
    name: string
    enabled: boolean
    createdAt: string
}

export type CreateOrganizationRequest = {
    name: string
}

export type UpdateOrganizationNameRequest = {
    name: string
}

export type UpdateOrganizationEnabledRequest = {
    enabled: boolean
}

export function getOrganizations(
    page = 0,
    size = 50
): Promise<PageResponse<Organization>> {
    const query = buildQueryString({
        page: normalizePage(page),
        size: normalizePageSize(size, 50, 200),
    })

    return apiRequest<PageResponse<Organization>>(
        `/api/organizations${query}`,
        { timeoutMs: 30_000 }
    )
}

export function createOrganization(
    request: CreateOrganizationRequest
): Promise<Organization> {
    return apiRequest<Organization>('/api/organizations', {
        method: 'POST',
        body: JSON.stringify(request),
        timeoutMs: 30_000,
    })
}

export function updateOrganizationName(
    organizationId: string,
    request: UpdateOrganizationNameRequest
): Promise<Organization> {
    return apiRequest<Organization>(
        `/api/organizations/${pathSegment(organizationId)}`,
        {
            method: 'PATCH',
            body: JSON.stringify(request),
            timeoutMs: 30_000,
        }
    )
}

export function updateOrganizationEnabled(
    organizationId: string,
    request: UpdateOrganizationEnabledRequest
): Promise<Organization> {
    return apiRequest<Organization>(
        `/api/organizations/${pathSegment(organizationId)}/enabled`,
        {
            method: 'PATCH',
            body: JSON.stringify(request),
            timeoutMs: 30_000,
        }
    )
}

export function getCurrentOrganization(): Promise<Organization> {
    return apiRequest<Organization>('/api/organizations/me', {
        timeoutMs: 20_000,
    })
}

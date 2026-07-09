// frontend/src/api/organizationApi.ts
import {apiRequest} from './http'
import type {PageResponse} from '../utils/page'

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

export async function getOrganizations(
    page = 0,
    size = 50
): Promise<PageResponse<Organization>> {
    return apiRequest<PageResponse<Organization>>(
        `/api/organizations?page=${page}&size=${size}`
    )
}

export async function createOrganization(
    request: CreateOrganizationRequest
): Promise<Organization> {
    return apiRequest<Organization>('/api/organizations', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}

export async function updateOrganizationName(
    organizationId: string,
    request: UpdateOrganizationNameRequest
): Promise<Organization> {
    return apiRequest<Organization>(
        `/api/organizations/${encodeURIComponent(organizationId)}`,
        {
            method: 'PATCH',
            body: JSON.stringify(request),
        }
    )
}

export async function updateOrganizationEnabled(
    organizationId: string,
    request: UpdateOrganizationEnabledRequest
): Promise<Organization> {
    return apiRequest<Organization>(
        `/api/organizations/${encodeURIComponent(organizationId)}/enabled`,
        {
            method: 'PATCH',
            body: JSON.stringify(request),
        }
    )
}

export async function getCurrentOrganization(): Promise<Organization> {
    return apiRequest<Organization>('/api/organizations/me')
}
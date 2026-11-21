// frontend/src/features/admin-organizations/lib/adminOrganizationsSupport.test.ts
import { describe, expect, it } from 'vitest'
import type { Organization } from '../../../entities/organization/api/organizationApi'
import { ApiError } from '../../../shared/api/http'
import {
    canMutateOrganization,
    getProtectionError,
    getProtectionLabel,
    isOrganizationRequestAborted,
    isOrganizationVersionConflict,
} from './adminOrganizationsSupport'

const tenant: Organization = {
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Компания',
    type: 'TENANT',
    protected: false,
    enabled: true,
    version: 2,
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T11:00:00Z',
}
const platform: Organization = {
    ...tenant,
    id: '00000000-0000-0000-0000-000000000001',
    type: 'PLATFORM',
    protected: true,
    name: 'SafeAI Platform',
}
const error = (status: number, code: string) => new ApiError('controlled', { code }, status)

describe('adminOrganizationsSupport: protection and concurrency guards', () => {
    it('protects PLATFORM and explicitly protected tenants from mutations', () => {
        expect(canMutateOrganization(tenant)).toBe(true)
        expect(canMutateOrganization(platform)).toBe(false)
        expect(canMutateOrganization({ ...tenant, protected: true })).toBe(false)
        expect(canMutateOrganization({ ...platform, protected: false })).toBe(false)

        expect(getProtectionError(platform)).toContain('Платформенная организация защищена')
        expect(getProtectionError(tenant)).toBe('Организация недоступна для изменения.')
        expect(getProtectionLabel(platform)).toBe('Защищённая системная организация')
        expect(getProtectionLabel(tenant)).toBe('Недоступно')
    })

    it.each([
        { status: 409, code: 'ORGANIZATION_VERSION_CONFLICT' },
        { status: 412, code: 'ORGANIZATION_VERSION_CONFLICT' },
        { status: 409, code: 'OPTIMISTIC_LOCK_CONFLICT' },
        { status: 412, code: 'OPTIMISTIC_LOCK_CONFLICT' },
    ])('recognizes actual optimistic conflicts: HTTP $status / $code', ({ status, code }) => {
        expect(isOrganizationVersionConflict(error(status, code))).toBe(true)
    })

    it('does not treat unrelated client or server failures as optimistic conflicts', () => {
        expect(isOrganizationVersionConflict(error(400, 'ORGANIZATION_VERSION_CONFLICT'))).toBe(false)
        expect(isOrganizationVersionConflict(error(500, 'OPTIMISTIC_LOCK_CONFLICT'))).toBe(false)
        expect(isOrganizationVersionConflict(error(409, 'VALIDATION_ERROR'))).toBe(false)
        expect(isOrganizationVersionConflict(new Error('409'))).toBe(false)
        expect(isOrganizationVersionConflict(null)).toBe(false)
    })

    it('recognizes only the explicit aborted request contract', () => {
        expect(isOrganizationRequestAborted(error(0, 'REQUEST_ABORTED'))).toBe(true)
        expect(isOrganizationRequestAborted(error(409, 'REQUEST_ABORTED'))).toBe(true)
        expect(isOrganizationRequestAborted(error(0, 'NETWORK_ERROR'))).toBe(false)
        expect(isOrganizationRequestAborted(new Error('REQUEST_ABORTED'))).toBe(false)
    })
})

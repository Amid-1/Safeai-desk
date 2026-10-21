// frontend/src/features/admin-organizations/lib/adminOrganizationsSupport.ts
// ============================================================
// frontend/src/features/admin-organizations/lib/adminOrganizationsSupport.ts
// ============================================================
import {
    isProtectedOrganization,
} from '../../../entities/organization/api/organizationApi'
import type {
    Organization,
    OrganizationDisableImpact,
} from '../../../entities/organization/api/organizationApi'
import {
    ApiError,
} from '../../../shared/api/http'

export type PendingOrganizationAction = {
    organizationId: string
    type:
        | 'RENAME'
        | 'DISABLE'
        | 'ENABLE'
} | null

export type DisableOrganizationDialogState = {
    organization: Organization
    impact: OrganizationDisableImpact
    confirmationName: string
} | null

export function canMutateOrganization(
    organization: Organization,
): boolean {
    return !isProtectedOrganization(
        organization,
    )
}

export function getProtectionError(
    organization: Organization,
): string {
    return isProtectedOrganization(
        organization,
    )
        ? (
            'Платформенная организация защищена '
            + 'и не изменяется через обычный '
            + 'organization-management.'
        )
        : 'Организация недоступна для изменения.'
}

export function getProtectionLabel(
    organization: Organization,
): string {
    return isProtectedOrganization(
        organization,
    )
        ? 'Защищённая системная организация'
        : 'Недоступно'
}

export function isOrganizationVersionConflict(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && (
            error.status === 409
            || error.status === 412
        )
        && (
            error.errorCode
                === 'ORGANIZATION_VERSION_CONFLICT'
            || error.errorCode
                === 'OPTIMISTIC_LOCK_CONFLICT'
        )
}

export function isOrganizationRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode === 'REQUEST_ABORTED'
}

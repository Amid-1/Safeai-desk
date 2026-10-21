// frontend/src/features/admin-users/ui/adminUsersViewTypes.ts
import type { AuthUser } from '../../auth/api/authApi'
import type { OrganizationDirectoryItem } from '../../../entities/organization/api/organizationApi'
import type { User, UserStatistics } from '../../../entities/user/api/userApi'

export type AdminUsersFilter =
    | 'ALL'
    | 'USER'
    | 'ADMIN'

export type AdminUsersViewProps = {
    users: User[]
    statistics: UserStatistics
    organizations: OrganizationDirectoryItem[]
    loadError: string
    mutationError: string
    success: string
    loading: boolean
    creating: boolean
    hasPendingMutation: boolean
    page: number
    totalPages: number
    filter: AdminUsersFilter
    status: 'ALL' | 'ACTIVE' | 'DISABLED'
    searchDraft: string
    appliedSearch: string
    totalMatched: number
    onSearchDraftChange: (value: string) => void
    onApplySearch: () => void
    onClearSearch: () => void
    onSelectSearchSuggestion: (user: User) => void
    onStatusChange: (value: 'ALL' | 'ACTIVE' | 'DISABLED') => void
    detailsLoadingUserId: string | null
    currentUser: AuthUser
    currentUserIsSuperAdmin: boolean
    onCreate: () => void
    onFilterChange:
        (filter: AdminUsersFilter) => void
    onPageChange:
        (page: number) => void
    onReload: () => void
    onDetails:
        (user: User) => void | Promise<void>
    onEdit:
        (user: User) => void
    onRoles:
        (user: User) => void
    onResetPassword:
        (user: User) => void
    onEnabledChange:
        (
            value: {
                user: User
                nextEnabled: boolean
            } | null
        ) => void
    onDelete:
        (user: User) => void
    canManageUser:
        (user: User) => boolean
    canPermanentlyDelete:
        (user: User) => boolean
}

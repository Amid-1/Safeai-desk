import type {
    AuthUser,
} from '../api/authApi'
import {
    getUserManagementRolePolicy,
} from '../domain/userManagementRolePolicy'
import PageErrorBoundary
    from '../components/PageErrorBoundary'
import {
    AdminUsersDialogs,
} from '../components/admin/users/AdminUsersDialogs'
import {
    AdminUsersView,
} from '../components/admin/users/AdminUsersView'
import {
    useAdminUsersDirectory,
} from './useAdminUsersDirectory'
import {
    useAdminUserCreate,
} from './useAdminUserCreate'
import {
    useAdminUserMutations,
} from './useAdminUserMutations'
import './AdminUsersPage.css'

type AdminUsersPageProps = {
    currentUser: AuthUser
}

function AdminUsersPage(
    props: AdminUsersPageProps,
) {
    return (
        <PageErrorBoundary>
            <AdminUsersPageContent
                {...props}
            />
        </PageErrorBoundary>
    )
}

function AdminUsersPageContent({
    currentUser,
}: AdminUsersPageProps) {
    const rolePolicy =
        getUserManagementRolePolicy(
            currentUser.roles,
        )

    const currentUserIsSuperAdmin =
        rolePolicy.canChooseRole

    const directory = useAdminUsersDirectory(
        currentUser,
        currentUserIsSuperAdmin,
    )

    const create = useAdminUserCreate({
        currentUser,
        currentUserIsSuperAdmin,
        organizations: directory.organizations,
        organizationsError:
            directory.organizationsError,
        requestReloadFromFirstPage:
            directory.requestReloadFromFirstPage,
    })

    const mutations = useAdminUserMutations({
        currentUser,
        currentUserIsSuperAdmin,
        organizations: directory.organizations,
        requestReloadFromFirstPage:
            directory.requestReloadFromFirstPage,
    })

    return (
        <div className="page users-page">
            <AdminUsersView
                users={directory.users}
                statistics={directory.statistics}
                organizations={
                    directory.organizations
                }
                loadError={directory.loadError}
                mutationError={
                    mutations.mutationError
                }
                success={
                    mutations.success
                    || create.success
                }
                loading={directory.loading}
                creating={create.creating}
                hasPendingMutation={
                    mutations.hasPendingMutation
                }
                page={directory.page}
                totalPages={directory.totalPages}
                filter={directory.filter}
                detailsLoadingUserId={
                    directory.detailsLoadingUserId
                }
                currentUser={currentUser}
                currentUserIsSuperAdmin={
                    currentUserIsSuperAdmin
                }
                onCreate={create.openCreateModal}
                onFilterChange={
                    directory.changeFilter
                }
                onPageChange={directory.setPage}
                onReload={directory.reload}
                onDetails={
                    directory.openDetailsModal
                }
                onEdit={mutations.openEditModal}
                onRoles={mutations.openRolesModal}
                onResetPassword={
                    mutations.openResetPasswordModal
                }
                onEnabledChange={
                    mutations.setConfirmState
                }
                onDelete={mutations.openDeleteModal}
                canManageUser={
                    mutations.canManageUser
                }
                canPermanentlyDelete={
                    mutations.canPermanentlyDelete
                }
            />

            <AdminUsersDialogs
                createModalOpen={
                    create.createModalOpen
                }
                createError={create.createError}
                email={create.email}
                password={create.password}
                passwordConfirm={
                    create.passwordConfirm
                }
                fullName={create.fullName}
                createRole={create.createRole}
                currentUserIsSuperAdmin={
                    currentUserIsSuperAdmin
                }
                organizationsLoading={
                    directory.organizationsLoading
                }
                organizations={
                    directory.organizations
                }
                selectedOrganizationId={
                    create.selectedOrganizationId
                }
                creating={create.creating}
                detailsUser={directory.detailsUser}
                detailsError={directory.detailsError}
                editUser={mutations.editUser}
                editEmail={mutations.editEmail}
                editFullName={mutations.editFullName}
                rolesUser={mutations.rolesUser}
                selectedRole={mutations.selectedRole}
                adminElevationConfirmed={
                    mutations.adminElevationConfirmed
                }
                resetPasswordUser={
                    mutations.resetPasswordUser
                }
                resetPasswordValue={
                    mutations.resetPasswordValue
                }
                resetPasswordConfirm={
                    mutations.resetPasswordConfirm
                }
                deleteUser={mutations.deleteUser}
                deleteConfirmationEmail={
                    mutations.deleteConfirmationEmail
                }
                modalError={mutations.modalError}
                hasPendingMutation={
                    mutations.hasPendingMutation
                }
                confirmState={mutations.confirmState}
                setEmail={create.setEmail}
                setPassword={create.setPassword}
                setPasswordConfirm={
                    create.setPasswordConfirm
                }
                setFullName={create.setFullName}
                setCreateRole={create.setCreateRole}
                setSelectedOrganizationId={
                    create.setSelectedOrganizationId
                }
                setEditEmail={mutations.setEditEmail}
                setEditFullName={
                    mutations.setEditFullName
                }
                setSelectedRole={mutations.setSelectedRole}
                setAdminElevationConfirmed={
                    mutations.setAdminElevationConfirmed
                }
                setResetPasswordValue={
                    mutations.setResetPasswordValue
                }
                setResetPasswordConfirm={
                    mutations.setResetPasswordConfirm
                }
                setDeleteConfirmationEmail={
                    mutations.setDeleteConfirmationEmail
                }
                setConfirmState={
                    mutations.setConfirmState
                }
                handleCreateUser={
                    create.handleCreateUser
                }
                closeCreateModal={
                    create.closeCreateModal
                }
                closeDetailsModal={
                    directory.closeDetailsModal
                }
                closeMutationModals={
                    mutations.closeMutationModals
                }
                submitEditUser={
                    mutations.submitEditUser
                }
                submitRoles={mutations.submitRoles}
                submitResetPassword={
                    mutations.submitResetPassword
                }
                submitPermanentDelete={
                    mutations.submitPermanentDelete
                }
                confirmEnabledChange={
                    mutations.confirmEnabledChange
                }
            />
        </div>
    )
}

export default AdminUsersPage

import PageErrorBoundary
    from '../components/PageErrorBoundary'
import {
    AdminOrganizationsView,
} from '../components/admin/organizations/AdminOrganizationsView'
import {
    AdminOrganizationDialogs,
} from '../components/admin/organizations/AdminOrganizationDialogs'
import {
    useAdminOrganizationsDirectory,
} from './useAdminOrganizationsDirectory'
import {
    useAdminOrganizationCreate,
} from './useAdminOrganizationCreate'
import {
    useAdminOrganizationMutations,
} from './useAdminOrganizationMutations'
import './AdminOrganizationsPage.css'

function AdminOrganizationsPage() {
    return (
        <PageErrorBoundary>
            <AdminOrganizationsPageContent />
        </PageErrorBoundary>
    )
}

function AdminOrganizationsPageContent() {
    const directory =
        useAdminOrganizationsDirectory()

    const mutations =
        useAdminOrganizationMutations({
            requestReloadFromFirstPage:
                directory.requestReloadFromFirstPage,
        })

    const create =
        useAdminOrganizationCreate({
            requestReloadFromFirstPage:
                directory.requestReloadFromFirstPage,
            setSuccess:
                mutations.setSuccess,
        })

    return (
        <div className="page organizations-page">
            <AdminOrganizationsView
                organizations={directory.organizations}
                loadError={directory.loadError}
                mutationError={mutations.mutationError}
                createError={create.createError}
                success={mutations.success}
                loading={directory.loading}
                creating={create.creating}
                hasPendingAction={mutations.hasPendingAction}
                page={directory.page}
                totalPages={directory.totalPages}
                name={create.name}
                detailsLoadingId={directory.detailsLoadingId}
                impactLoadingId={mutations.impactLoadingId}
                onNameChange={create.setName}
                onCreate={create.submitCreateOrganization}
                onReload={directory.reloadCurrentPage}
                onDetails={directory.openDetailsModal}
                onRename={mutations.openRenameModal}
                onDisable={mutations.openDisableDialog}
                onEnable={mutations.openEnableDialog}
                onPageChange={directory.setPage}
            />

            <AdminOrganizationDialogs
                detailsOrganization={directory.detailsOrganization}
                detailsError={directory.detailsError}
                renameOrganization={mutations.renameOrganization}
                renameValue={mutations.renameValue}
                renameError={mutations.renameError}
                disableDialog={mutations.disableDialog}
                enableOrganizationTarget={mutations.enableOrganizationTarget}
                hasPendingAction={mutations.hasPendingAction}
                onCloseDetails={directory.closeDetailsModal}
                onCloseRename={mutations.closeRenameModal}
                onRenameValueChange={mutations.setRenameValue}
                onSubmitRename={mutations.submitRenameOrganization}
                onCloseDisable={mutations.closeDisableDialog}
                onDisableConfirmationChange={
                    mutations.setDisableConfirmationName
                }
                onConfirmDisable={mutations.confirmDisableOrganization}
                onCloseEnable={mutations.closeEnableDialog}
                onConfirmEnable={mutations.confirmEnableOrganization}
            />
        </div>
    )
}

export default AdminOrganizationsPage

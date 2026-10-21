// frontend/src/pages/AdminOrganizationsPage.tsx
import PageErrorBoundary from '../shared/ui/PageErrorBoundary'
import { AdminOrganizationsView } from '../features/admin-organizations/ui/AdminOrganizationsView'
import { AdminOrganizationDialogs } from '../features/admin-organizations/ui/AdminOrganizationDialogs'
import { useAdminOrganizationsDirectory } from '../features/admin-organizations/lib/useAdminOrganizationsDirectory'
import { useAdminOrganizationCreate } from '../features/admin-organizations/lib/useAdminOrganizationCreate'
import { useAdminOrganizationMutations } from '../features/admin-organizations/lib/useAdminOrganizationMutations'
import '../features/admin-organizations/styles/AdminOrganizationsPage.css'
import '../features/admin-organizations/styles/AdminOrganizationsWorkspace.css'
import '../features/admin-organizations/styles/AdminOrganizationsClarity.css'

function AdminOrganizationsPage() {
    return <PageErrorBoundary><AdminOrganizationsPageContent /></PageErrorBoundary>
}

function AdminOrganizationsPageContent() {
    const directory = useAdminOrganizationsDirectory()
    const mutations = useAdminOrganizationMutations({
        requestReloadFromFirstPage: directory.requestReloadFromFirstPage,
    })
    const create = useAdminOrganizationCreate({
        requestReloadFromFirstPage: directory.requestReloadFromFirstPage,
        setSuccess: mutations.setSuccess,
    })

    return <div className="page organizations-page">
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
            impactLoadingId={mutations.impactLoadingId}
            onCloseDetails={directory.closeDetailsModal}
            onCloseRename={mutations.closeRenameModal}
            onRenameValueChange={mutations.setRenameValue}
            onSubmitRename={mutations.submitRenameOrganization}
            onCloseDisable={mutations.closeDisableDialog}
            onDisableConfirmationChange={mutations.setDisableConfirmationName}
            onConfirmDisable={mutations.confirmDisableOrganization}
            onCloseEnable={mutations.closeEnableDialog}
            onConfirmEnable={mutations.confirmEnableOrganization}
            onDisable={mutations.openDisableDialog}
            onEnable={mutations.openEnableDialog}
            onRename={mutations.openRenameModal}
        />
    </div>
}

export default AdminOrganizationsPage

// frontend/src/features/models/ui/access/ModelsAccessWorkspace.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { PolicyCard } from '../ModelControlPlaneViews'
import type { PolicyEditorTarget } from '../modelPolicyModal/policyFormTabsConfig'

export function ModelsAccessWorkspace({ controller, onOpenPolicy, onOrganizationNavigation }: {
    controller: AdminModelsController
    onOpenPolicy: (target?: PolicyEditorTarget) => void
    onOrganizationNavigation: (id: string) => void
}) {
    const { runtime, policy, catalog, effectiveCatalog, targetOrganizationId,
        selectedOrganizationName, isSuperAdmin,
        organizationQuery, organizationResults, organizationSearchPending,
        organizationSearchStatus, organizationSearchError, handleOrganizationQueryChange,
        handleOrganizationSearch, cancelOrganizationSearch, setTargetOrganizationId,
        setSelectedOrganization, setOrganizationResults, mutationPending } = controller
    if (!runtime || !policy) return null
    return (
        <section className="models-section-stack" aria-labelledby="models-access-title">
            <header className="models-section-heading models-section-heading--actions">
                <div>
                    <h2 id="models-access-title">Правила использования моделей</h2>
                </div>
            </header>
            <div className="models-access-grid">
                <PolicyCard policy={policy} catalog={catalog} effectiveCatalog={effectiveCatalog}
                    showOrganizationScope={false}
                    runtime={runtime} organizationId={targetOrganizationId} organizationName={selectedOrganizationName}
                    isSuperAdmin={isSuperAdmin} organizationQuery={organizationQuery}
                    organizationResults={organizationResults} organizationSearchPending={organizationSearchPending}
                    organizationSearchStatus={organizationSearchStatus} organizationSearchError={organizationSearchError}
                    onOrganizationQueryChange={handleOrganizationQueryChange}
                    onOrganizationSearch={handleOrganizationSearch}
                    onOrganizationSelect={(organization) => {
                        cancelOrganizationSearch()
                        setTargetOrganizationId(organization.id)
                        onOrganizationNavigation(organization.id)
                        handleOrganizationQueryChange('')
                        setSelectedOrganization({ id: organization.id, name: organization.name })
                        setOrganizationResults([])
                    }}
                    editDisabled={mutationPending}
                    onEdit={onOpenPolicy} />
            </div>
        </section>
    )
}

// frontend/src/features/models/ui/access/ModelsAccessWorkspace.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { PolicyCard } from '../ModelControlPlaneViews'

export function ModelsAccessWorkspace({ controller }: { controller: AdminModelsController }) {
    const { runtime, policy, catalog, effectiveCatalog, targetOrganizationId,
        selectedOrganizationName, isSuperAdmin, setPolicyModalOpen,
        organizationQuery, organizationResults, organizationSearchPending,
        organizationSearchStatus, organizationSearchError, handleOrganizationQueryChange,
        handleOrganizationSearch, cancelOrganizationSearch, setTargetOrganizationId,
        setSelectedOrganization, setOrganizationResults, mutationPending } = controller
    if (!runtime || !policy) return null
    return (
        <section className="models-section-stack" aria-labelledby="models-access-title">
            <header className="models-section-heading models-section-heading--actions">
                <div>
                    <span className="models-eyebrow">ОРГАНИЗАЦИИ И ОГРАНИЧЕНИЯ</span>
                    <h2 id="models-access-title">Доступ организаций</h2>
                    <p>Выберите организацию для просмотра её правил. Настройки других организаций доступны только SUPER_ADMIN.</p>
                </div>
                <button type="button" className="btn-primary" disabled={mutationPending}
                    onClick={() => setPolicyModalOpen(true)}>Изменить правила</button>
            </header>
            <div className="models-access-grid">
                <PolicyCard policy={policy} catalog={catalog} effectiveCatalog={effectiveCatalog}
                    runtime={runtime} organizationId={targetOrganizationId} organizationName={selectedOrganizationName}
                    isSuperAdmin={isSuperAdmin} organizationQuery={organizationQuery}
                    organizationResults={organizationResults} organizationSearchPending={organizationSearchPending}
                    organizationSearchStatus={organizationSearchStatus} organizationSearchError={organizationSearchError}
                    onOrganizationQueryChange={handleOrganizationQueryChange}
                    onOrganizationSearch={handleOrganizationSearch}
                    onOrganizationSelect={(organization) => {
                        cancelOrganizationSearch()
                        setTargetOrganizationId(organization.id)
                        handleOrganizationQueryChange('')
                        setSelectedOrganization({ id: organization.id, name: organization.name })
                        setOrganizationResults([])
                    }}
                    onEdit={() => setPolicyModalOpen(true)} />
            </div>
        </section>
    )
}

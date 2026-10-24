// frontend/src/features/models/ui/AdminModelsPageView.tsx
// Композиция вкладок и модальных окон. Запросы и RBAC принадлежат feature-контроллеру.
import { useSearchParams } from 'react-router-dom'
import { ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import type { AdminModelsController } from '../controller/useAdminModelsController'
import { AdminModelsUpper } from './AdminModelsUpper'
import { AdminModelsNavigation, parseModelsSection } from './AdminModelsNavigation'
import type { ModelsSection } from './AdminModelsNavigation'
import { ModelsOverview } from './overview/ModelsOverview'
import { ModelsCatalogWorkspace } from './catalog/ModelsCatalogWorkspace'
import { ModelsAccessWorkspace } from './access/ModelsAccessWorkspace'
import { ModelsDiagnosticsWorkspace } from './diagnostics/ModelsDiagnosticsWorkspace'
import { ModelCatalogHistoryModal } from './ModelCatalogHistoryModal'
import { ModelCatalogVersionModal } from './ModelCatalogVersionModal'
import { ModelPolicyModal } from './ModelPolicyModal'

export function AdminModelsPageView({ controller }: { controller: AdminModelsController }) {
    const [params, setParams] = useSearchParams()
    const section = parseModelsSection(params.get('section'))
    const { currentUser, loading, loadedOrganizationId, runtime, policy, error,
        targetOrganizationId, catalog, effectiveCatalog, mutationPending,
        selectedOrganizationName, setPolicyModalOpen, catalogModal, setCatalogModal,
        historyModalEntry, setHistoryModalEntry, policyModalOpen, catalogByKey,
        handleCreateCatalogVersion, handleCreatePolicyVersion, handlePreviewPolicy,
        notice } = controller

    function navigate(next: ModelsSection) {
        if (next === section) return
        const updated = new URLSearchParams(params)
        if (next === 'overview') updated.delete('section')
        else updated.set('section', next)
        setParams(updated, { preventScrollReset: true })
    }

    if (!currentUser) return null
    if (loading) return <div className="page models-page models-page--sections"><LoadingState message="Загрузка настроек моделей..." /></div>
    if (error && (!runtime || !policy)) return <div className="page models-page models-page--sections"><ErrorState message={error} /></div>
    if (!runtime || !policy) return <div className="page models-page models-page--sections"><ErrorState message="Сервер вернул неполные данные о настройках моделей." /></div>
    // Security invariant: never render a previous tenant's policy, even for one frame.
    if (loadedOrganizationId !== targetOrganizationId) return (
        <div className="page models-page models-page--sections">
            {error ? <ErrorState message={error} /> : <LoadingState message="Загрузка настроек моделей..." />}
        </div>
    )
    if (policy.organizationId !== targetOrganizationId) return (
        <div className="page models-page models-page--sections">
            <ErrorState message="Сервер вернул правила другой организации. Обновите страницу." />
        </div>
    )

    return (
        <div className="page models-page models-page--sections">
            <AdminModelsUpper runtime={runtime} />
            <AdminModelsNavigation section={section} onSelect={navigate} />
            {error && <div className="models-page-alert"><ErrorState message={error} variant="inline" /></div>}
            {notice && <div className="models-notice" role="status">{notice}</div>}
            <div id="models-section-panel" className="models-section-panel" role="tabpanel"
                aria-labelledby={`models-tab-${section}`} tabIndex={0}>
                {section === 'overview' && <ModelsOverview controller={controller} navigate={navigate} />}
                {section === 'catalog' && <ModelsCatalogWorkspace controller={controller} />}
                {section === 'access' && <ModelsAccessWorkspace controller={controller} />}
                {section === 'diagnostics' && <ModelsDiagnosticsWorkspace controller={controller} />}
            </div>
            {historyModalEntry && <ModelCatalogHistoryModal
                modelKey={historyModalEntry.modelKey} displayName={historyModalEntry.displayName}
                runtime={runtime} onClose={() => setHistoryModalEntry(null)} />}
            {catalogModal && <ModelCatalogVersionModal
                base={catalogModal.base} runtime={runtime} catalogByKey={catalogByKey}
                pending={mutationPending} onClose={() => setCatalogModal(null)}
                onSubmit={handleCreateCatalogVersion} />}
            {policyModalOpen && <ModelPolicyModal
                policy={policy} catalog={catalog} effectiveCatalog={effectiveCatalog} runtime={runtime}
                organizationId={targetOrganizationId} organizationName={selectedOrganizationName}
                pending={mutationPending} onClose={() => setPolicyModalOpen(false)}
                onPreview={handlePreviewPolicy} onSubmit={handleCreatePolicyVersion} />}
        </div>
    )
}

// frontend/src/features/models/ui/AdminModelsPageView.tsx
// Композиция вкладок и модальных окон. Запросы и RBAC принадлежат feature-контроллеру.
import { useSearchParams } from 'react-router-dom'
import { useLayoutEffect, useRef, useState } from 'react'
import { ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { directoryUuid } from '../../../shared/lib/adminDirectoryUrl'
import type { AdminModelsController } from '../controller/useAdminModelsController'
import { AdminModelsUpper } from './AdminModelsUpper'
import { AdminModelsNavigation } from './AdminModelsNavigation'
import { DIAGNOSTICS_TARGET_IDS, parseDiagnosticsTarget, parseModelsSection } from './modelsNavigationState'
import type { DiagnosticsTarget, ModelsSection } from './modelsNavigationState'
import type { ModelCatalogEntry } from '../../../entities/model/api/modelApi'
import { ModelsOverview } from './overview/ModelsOverview'
import { ModelsCatalogWorkspace } from './catalog/ModelsCatalogWorkspace'
import { ModelsAccessWorkspace } from './access/ModelsAccessWorkspace'
import { ModelsDiagnosticsWorkspace } from './diagnostics/ModelsDiagnosticsWorkspace'
import { ModelCatalogHistoryModal } from './ModelCatalogHistoryModal'
import { ModelCatalogVersionModal } from './ModelCatalogVersionModal'
import { ModelPolicyModal } from './ModelPolicyModal'
import type { PolicyEditorTarget } from './modelPolicyModal/policyFormTabsConfig'
import { ModelsOrganizationScope } from './modelControlPlaneViews/PolicyCard'
import type { OrganizationDirectoryItem } from '../../../entities/organization/api/organizationApi'

export function AdminModelsPageView({ controller }: { controller: AdminModelsController }) {
    const [params, setParams] = useSearchParams()
    const section = parseModelsSection(params.get('section'))
    const diagnosticTarget = section === 'diagnostics' && !params.get('decisionId') ? parseDiagnosticsTarget(params.get('focus')) : null
    const supportsLinkedHistory = section === 'catalog' || section === 'diagnostics'
    const linkedModelKey = supportsLinkedHistory ? params.get('modelKey') : null
    const linkedVersionId = supportsLinkedHistory ? params.get('versionId') : null
    const usageModel = section === 'catalog' ? params.get('usageModel') : null
    const pageRef = useRef<HTMLDivElement>(null)
    const [policyTarget, setPolicyTarget] = useState<PolicyEditorTarget | undefined>()
    const { currentUser, loading, loadedOrganizationId, runtime, policy, error,
        targetOrganizationId, catalog, effectiveCatalog, mutationPending,
        selectedOrganizationName, setPolicyModalOpen, catalogModal, setCatalogModal,
        historyModalEntry, setHistoryModalEntry, policyModalOpen, catalogByKey,
        handleCreateCatalogVersion, handleCreatePolicyVersion, handlePreviewPolicy,
        notice, isSuperAdmin, setTargetOrganizationId, setSelectedOrganization,
        organizationQuery, organizationResults, organizationSearchPending, organizationSearchStatus,
        organizationSearchError, handleOrganizationQueryChange, handleOrganizationSearch,
        handleRouteDecisionIdChange } = controller
    const requestedOrganizationId = (isSuperAdmin ? directoryUuid(params.get('organizationId')) : '') || currentUser?.organizationId || ''
    useLayoutEffect(() => {
        if (requestedOrganizationId && targetOrganizationId !== requestedOrganizationId) {
            setSelectedOrganization(null)
            setTargetOrganizationId(requestedOrganizationId)
            setPolicyModalOpen(false)
            setPolicyTarget(undefined)
            handleRouteDecisionIdChange('')
        }
    }, [requestedOrganizationId, targetOrganizationId, setSelectedOrganization, setTargetOrganizationId, setPolicyModalOpen, handleRouteDecisionIdChange])
    useLayoutEffect(() => {
        if (pageRef.current) pageRef.current.scrollTop = 0
        // The Models route is not a viewport workspace: its scroll owner is the document.
        if (document.scrollingElement) document.scrollingElement.scrollTop = 0
    }, [section, loading])

    useLayoutEffect(() => {
        if (!diagnosticTarget || loading || loadedOrganizationId !== targetOrganizationId) return
        const target = pageRef.current?.querySelector<HTMLElement>(`#${DIAGNOSTICS_TARGET_IDS[diagnosticTarget]}`)
        target?.focus({ preventScroll: true })
        target?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
    }, [diagnosticTarget, loading, loadedOrganizationId, targetOrganizationId])

    function navigate(next: ModelsSection, target?: DiagnosticsTarget) {
        if (next === section && !target) return
        setHistoryModalEntry(null)
        const updated = new URLSearchParams(params)
        updated.delete('decisionId')
        updated.delete('focus')
        updated.delete('modelKey')
        updated.delete('versionId')
        updated.delete('usageModel')
        if (next === 'diagnostics' && target) updated.set('focus', target)
        if (next === 'overview') updated.delete('section')
        else updated.set('section', next)
        if (updated.toString() === params.toString()) return
        setParams(updated, { preventScrollReset: true })
    }

    function openCatalogVersion(entry: ModelCatalogEntry) {
        const updated = new URLSearchParams(params)
        // This is a read-only modal, not a change of workspace. Keep the
        // diagnostic selection, decision link and underlying component state.
        updated.set('modelKey', entry.modelKey)
        updated.set('versionId', entry.id)
        setParams(updated, { preventScrollReset: true })
    }

    function closeHistory() {
        setHistoryModalEntry(null)
        if (linkedModelKey || linkedVersionId || usageModel) {
            const updated = new URLSearchParams(params)
            updated.delete('modelKey')
            updated.delete('versionId')
            updated.delete('usageModel')
            setParams(updated, { replace: true, preventScrollReset: true })
        }
    }

    function openPolicy(target?: PolicyEditorTarget) {
        if (mutationPending) return
        setPolicyTarget(target)
        setPolicyModalOpen(true)
    }

    function navigateOrganization(id: string) {
        if (!isSuperAdmin || mutationPending || id === requestedOrganizationId) return
        const updated = new URLSearchParams(params)
        updated.set('organizationId', id)
        // The previous tenant's historical decision and open editor are not the new scope.
        updated.delete('decisionId')
        setPolicyModalOpen(false)
        setPolicyTarget(undefined)
        setParams(updated, { preventScrollReset: true })
    }
    function selectOrganization(organization: OrganizationDirectoryItem) {
        if (!isSuperAdmin || mutationPending) return
        handleOrganizationQueryChange('')
        navigateOrganization(organization.id)
    }
    const scope = requestedOrganizationId && <ModelsOrganizationScope
        organizationId={requestedOrganizationId}
        organizationName={targetOrganizationId === requestedOrganizationId ? selectedOrganizationName : null}
        isSuperAdmin={isSuperAdmin} organizationQuery={organizationQuery}
        organizationResults={organizationResults} organizationSearchPending={organizationSearchPending}
        organizationSearchStatus={organizationSearchStatus} organizationSearchError={organizationSearchError}
        onOrganizationQueryChange={handleOrganizationQueryChange} onOrganizationSearch={handleOrganizationSearch}
        onOrganizationSelect={selectOrganization} editDisabled={mutationPending || policyModalOpen} />

    if (!currentUser) return null
    if (loading) return <div className="page models-page models-page--sections">{scope}<LoadingState message="Загрузка настроек моделей..." /></div>
    if (error && (!runtime || !policy)) return <div className="page models-page models-page--sections">{scope}<ErrorState message={error} /></div>
    if (!runtime || !policy) return <div className="page models-page models-page--sections">{scope}<ErrorState message="Сервер вернул неполные данные о настройках моделей." /></div>
    // Security invariant: never render a previous tenant's policy, even for one frame.
    if (loadedOrganizationId !== targetOrganizationId || targetOrganizationId !== requestedOrganizationId) return (
        <div className="page models-page models-page--sections">
            {scope}
            {error ? <ErrorState message={error} /> : <LoadingState message="Загрузка настроек моделей..." />}
        </div>
    )
    if (policy.organizationId !== targetOrganizationId) return (
        <div className="page models-page models-page--sections">
            {scope}
            <ErrorState message="Сервер вернул правила другой организации. Обновите страницу." />
        </div>
    )

    // Resolve the model only from the accessible catalog; history loads the exact version ID.
    // Usage reports carry a provider model identifier, not a catalog version.
    // Only resolve an exact unambiguous match; never guess among providers.
    const usageMatches = usageModel
        ? [...effectiveCatalog, ...catalog].filter(entry => entry.providerModelId === usageModel) : []
    const usageKeys = new Set(usageMatches.map(entry => entry.modelKey))
    const linkedEntry = linkedModelKey && linkedVersionId
        ? [...effectiveCatalog, ...catalog].find(entry => entry.modelKey === linkedModelKey)
        : usageKeys.size === 1 ? usageMatches[0] : null
    const historyEntry = linkedEntry ?? (section === 'catalog' ? historyModalEntry : null)

    return (
        <div ref={pageRef} className="page models-page models-page--sections">
            <AdminModelsUpper runtime={runtime} />
            {scope}
            <AdminModelsNavigation section={section} onSelect={navigate} />
            {error && <div className="models-page-alert"><ErrorState message={error} variant="inline" /></div>}
            {notice && <div className="models-notice" role="status">{notice}</div>}
            <div id="models-section-panel" className="models-section-panel" role="tabpanel"
                aria-labelledby={`models-tab-${section}`} tabIndex={0}>
                {(linkedModelKey || linkedVersionId || usageModel) && !linkedEntry && <div className="models-notice" role="status">
                    {usageKeys.size > 1 ? 'Найдено несколько моделей с этим идентификатором. Выберите нужную в каталоге.' : 'Модель из ссылки не найдена в доступном каталоге.'} <button type="button" className="secondary-button" onClick={closeHistory}>Закрыть уведомление</button>
                </div>}
                {section === 'overview' && <ModelsOverview controller={controller} navigate={navigate} onOpenPolicy={() => openPolicy('preview')} />}
                {section === 'catalog' && <ModelsCatalogWorkspace controller={controller} />}
                {section === 'access' && <ModelsAccessWorkspace controller={controller} onOpenPolicy={openPolicy}
                    onOrganizationNavigation={navigateOrganization} />}
                {section === 'diagnostics' && <ModelsDiagnosticsWorkspace controller={controller} onOpenCatalogVersion={openCatalogVersion} />}
            </div>
            {historyEntry && <ModelCatalogHistoryModal key={`${historyEntry.modelKey}:${linkedEntry ? linkedVersionId : ''}`}
                modelKey={historyEntry.modelKey} displayName={historyEntry.displayName}
                targetVersionId={linkedEntry ? linkedVersionId ?? undefined : undefined}
                runtime={runtime} onClose={closeHistory} />}
            {controller.isSuperAdmin && catalogModal && <ModelCatalogVersionModal
                base={catalogModal.base} runtime={runtime} catalogByKey={catalogByKey}
                pending={mutationPending} onClose={() => setCatalogModal(null)}
                onSubmit={handleCreateCatalogVersion} />}
            {policyModalOpen && <ModelPolicyModal key={targetOrganizationId}
                initialTarget={policyTarget}
                policy={policy} catalog={catalog} effectiveCatalog={effectiveCatalog} runtime={runtime}
                organizationId={targetOrganizationId} organizationName={selectedOrganizationName}
                pending={mutationPending} onClose={() => setPolicyModalOpen(false)}
                onPreview={handlePreviewPolicy} onSubmit={handleCreatePolicyVersion} />}
        </div>
    )
}

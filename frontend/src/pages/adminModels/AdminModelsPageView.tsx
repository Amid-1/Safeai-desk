// ============================================================
// frontend/src/pages/adminModels/AdminModelsPageView.tsx
// ============================================================
import ResizableScrollRegion from '../../components/ResizableScrollRegion'
import { ErrorState, LoadingState } from '../../components/StateBlock'
import { CatalogTable } from '../../components/admin/models/ModelControlPlaneViews'
import { ModelCatalogHistoryModal } from '../../components/admin/models/ModelCatalogHistoryModal'
import { ModelCatalogVersionModal } from '../../components/admin/models/ModelCatalogVersionModal'
import { ModelPolicyModal } from '../../components/admin/models/ModelPolicyModal'
import type { AdminModelsController } from './useAdminModelsController'
import CatalogEmptyState from './CatalogEmptyState'
import { AdminModelsUpper } from './AdminModelsUpper'

export function AdminModelsPageView({ controller }: { controller: AdminModelsController }) {
    const {
        currentUser,
        loading,
        loadedOrganizationId,
        runtime,
        policy,
        error,
        targetOrganizationId,
        catalog,
        effectiveCatalog,
        isSuperAdmin,
        mutationPending,
        handleImportRuntime,
        selectedOrganizationName,
        setPolicyModalOpen,
        catalogModal,
        setCatalogModal,
        historyModalEntry,
        setHistoryModalEntry,
        policyModalOpen,
        catalogMinUpperHeight,
        catalogByKey,
        handleCreateCatalogVersion,
        handleCreatePolicyVersion,
        handlePreviewPolicy,
    } = controller

    if (!currentUser) {
        return null
    }

    if (loading) {
        return (
            <div className="page models-page">
                <LoadingState message="Загрузка настроек моделей..." />
            </div>
        )
    }

    if (
        error
        && (!runtime || !policy)
    ) {
        return (
            <div className="page models-page">
                <ErrorState message={error} />
            </div>
        )
    }

    if (!runtime || !policy) {
        return (
            <div className="page models-page">
                <ErrorState message="Сервер вернул неполные данные о настройках моделей." />
            </div>
        )
    }

    // Между выбором организации и запуском её GET нельзя показывать
    // данные предыдущего tenant даже на один кадр.
    if (loadedOrganizationId !== targetOrganizationId) {
        return (
            <div className="page models-page">
                {error && !loading
                    ? <ErrorState message={error} />
                    : <LoadingState message="Загрузка настроек моделей..." />}
            </div>
        )
    }

    // Проверяем принадлежность даже при HTTP 200 от backend.
    if (policy.organizationId !== targetOrganizationId) {
        return (
            <div className="page models-page">
                <ErrorState message="Сервер вернул правила другой организации. Обновите страницу." />
            </div>
        )
    }

    const upper = <AdminModelsUpper controller={controller} runtime={runtime} policy={policy} />

    const footer = (
        <div className="pagination pagination--single models-catalog-footer">
            <div className="pagination__summary">
                <strong>
                    Сводка
                </strong>
                <span>
                    Моделей: {catalog.length}
                    {' · '}
                    Действующих версий: {effectiveCatalog.length}
                    {' · '}
                    Runtime: {runtime.provider}/{runtime.model}
                    {' · '}
                    Правила:{' '}
                    {policy.configured
                        ? `версия ${policy.version}`
                        : 'не настроены'}
                </span>
            </div>
        </div>
    )

    return (
        <div className="page models-page">
            <ResizableScrollRegion
                upper={upper}
                footer={footer}
                storageKey="safeai:models-catalog-height:v6"
                label="каталог моделей"
                upperClassName="models-page__upper"
                lowerClassName="models-page__lower"
                viewportClassName="models-catalog-scroll"
                footerClassName="models-page__footer"
                defaultHeight={280}
                autoFitHeight={catalog.length === 0
                    ? 310
                    : Math.min(540, 148 + catalog.length * 150)}
                minHeight={170}
                maxHeight={760}
                preserveUpperContent
                upperBoundaryPadding={12}
                minUpperHeight={catalogMinUpperHeight}
            >
                {catalog.length === 0 ? (
                    <CatalogEmptyState
                        isSuperAdmin={isSuperAdmin}
                        mutationPending={mutationPending}
                        onImportRuntime={() => {
                            void handleImportRuntime()
                        }}
                        onCreateModel={() => {
                            setCatalogModal({
                                base: null,
                            })
                        }}
                    />
                ) : (
                    <CatalogTable
                        entries={catalog}
                        effectiveEntries={effectiveCatalog}
                        runtime={runtime}
                        canEdit={isSuperAdmin}
                        onCreateVersion={(entry) => {
                            setCatalogModal({
                                base: entry,
                            })
                        }}
                        onOpenHistory={(entry) => {
                            setHistoryModalEntry(entry)
                        }}
                    />
                )}
            </ResizableScrollRegion>

            {historyModalEntry && (
                <ModelCatalogHistoryModal
                    modelKey={historyModalEntry.modelKey}
                    displayName={historyModalEntry.displayName}
                    runtime={runtime}
                    onClose={() => {
                        setHistoryModalEntry(null)
                    }}
                />
            )}

            {catalogModal && (
                <ModelCatalogVersionModal
                    base={catalogModal.base}
                    runtime={runtime}
                    catalogByKey={catalogByKey}
                    pending={mutationPending}
                    onClose={() => {
                        setCatalogModal(null)
                    }}
                    onSubmit={
                        handleCreateCatalogVersion
                    }
                />
            )}

            {policyModalOpen && (
                <ModelPolicyModal
                    policy={policy}
                    catalog={catalog}
                    effectiveCatalog={effectiveCatalog}
                    runtime={runtime}
                    organizationId={targetOrganizationId}
                    organizationName={selectedOrganizationName}
                    pending={mutationPending}
                    onClose={() => {
                        setPolicyModalOpen(false)
                    }}
                    onPreview={
                        handlePreviewPolicy
                    }
                    onSubmit={
                        handleCreatePolicyVersion
                    }
                />
            )}
        </div>
    )
}

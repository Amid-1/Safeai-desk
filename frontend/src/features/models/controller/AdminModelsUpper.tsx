// frontend/src/features/models/controller/AdminModelsUpper.tsx
// ============================================================
// frontend/src/features/models/controller/AdminModelsUpper.tsx
// ============================================================
import { ErrorState } from '../../../shared/ui/StateBlock'
import { PolicyCard, RouteDecisionEvidence, RuntimeCard } from '../ui/ModelControlPlaneViews'
import { ModelAvailabilitySummary } from '../ui/ModelAvailabilitySummary'
import type { AdminModelsController } from './useAdminModelsController'
import type { RuntimeModelStatus, OrganizationModelPolicy } from '../../../entities/model/api/modelApi'

function connectionModeLabel(
    value: string,
) {
    switch (value) {
        case 'SINGLE_PROVIDER_STATIC':
            return 'Один фиксированный провайдер и модель'
        default:
            return 'Runtime сконфигурирован'
    }
}


export function AdminModelsUpper({
    controller, runtime, policy,
}: {
    controller: AdminModelsController
    runtime: RuntimeModelStatus
    policy: OrganizationModelPolicy
}) {
    const {
        catalog,
        effectiveCatalog,
        isSuperAdmin,
        heroRef,
        mutationPending,
        setPolicyModalOpen,
        setCatalogModal,
        handleImportRuntime,
        setCatalogPermissionNotice,
        catalogPermissionNotice,
        error,
        notice,
        runtimeProbe,
        runtimeProbePending,
        handleRuntimeProbe,
        targetOrganizationId,
        selectedOrganizationName,
        organizationQuery,
        setOrganizationQuery,
        handleOrganizationQueryChange,
        cancelOrganizationSearch,
        organizationResults,
        organizationSearchPending,
        handleOrganizationSearch,
        setTargetOrganizationId,
        setSelectedOrganization,
        setOrganizationResults,
        routeDecisionId,
        handleRouteDecisionIdChange,
        routeError,
        routePending,
        routeDecision,
        handleRouteLookup,
    } = controller

    return (
        <div className="models-page__upper-content">
            <header
                ref={heroRef}
                className="models-hero"
            >
                <div className="models-hero__content">
                    <span className="models-eyebrow">
                        УПРАВЛЕНИЕ МОДЕЛЯМИ
                    </span>

                    <h1>
                        Модели и маршрутизация
                    </h1>

                    <p>
                        Runtime показывает физически подключённую модель.
                        Каталог хранит неизменяемые версии её параметров.
                        Правила ограничивают использование для выбранной
                        организации.
                    </p>
                </div>

                <div className="models-hero__side">
                    <div className="models-hero__actions" aria-label="Управление моделями">
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={mutationPending}
                            onClick={() => setPolicyModalOpen(true)}
                        >
                            Изменить правила
                        </button>
                        {isSuperAdmin && (
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={mutationPending}
                                onClick={() => { void handleImportRuntime() }}
                            >
                                Добавить Runtime в каталог
                            </button>
                        )}
                        <button
                            type="button"
                            className={isSuperAdmin ? 'btn-primary' : 'secondary-button'}
                            disabled={mutationPending}
                            onClick={() => {
                                if (!isSuperAdmin) {
                                    setCatalogPermissionNotice(true)
                                    return
                                }
                                setCatalogModal({ base: null })
                            }}
                        >
                            Создать модель
                        </button>
                    </div>
                    <div className="models-runtime-badge">
                    <span>Runtime настроен на</span>
                    <strong>
                        {runtime.provider} · {runtime.model}
                    </strong>
                    <small>
                        {connectionModeLabel(
                            runtime.routingMode,
                        )}
                    </small>
                    </div>
                    {!isSuperAdmin && catalogPermissionNotice && (
                        <p className="models-hero__permission-notice" role="status">
                            Создание и изменение версий каталога доступно только SUPER_ADMIN.
                            Администратор организации может менять правила своей организации.
                        </p>
                    )}
                </div>
            </header>
            <span
                className="resizable-scroll-region__upper-boundary-marker"
                data-resizable-upper-boundary
                aria-hidden="true"
            />

            {error && (
                <ErrorState
                    message={error}
                    variant="inline"
                />
            )}

            {notice && (
                <div
                    className="models-notice"
                    role="status"
                >
                    {notice}
                </div>
            )}

            <ModelAvailabilitySummary
                runtime={runtime}
                effectiveCatalog={effectiveCatalog}
                policy={policy}
                organizationName={selectedOrganizationName}
                onOpenPolicy={() => setPolicyModalOpen(true)}
            />

            <section className="models-overview-grid">
                <RuntimeCard
                    runtime={runtime}
                    effectiveCatalog={effectiveCatalog}
                    probe={runtimeProbe}
                    probePending={runtimeProbePending}
                    canProbe={isSuperAdmin}
                    onProbe={() => {
                        void handleRuntimeProbe()
                    }}
                />

                <PolicyCard
                    policy={policy}
                    catalog={catalog}
                    effectiveCatalog={effectiveCatalog}
                    runtime={runtime}
                    organizationId={targetOrganizationId}
                    organizationName={selectedOrganizationName}
                    isSuperAdmin={isSuperAdmin}
                    organizationQuery={organizationQuery}
                    organizationResults={organizationResults}
                    organizationSearchPending={
                        organizationSearchPending
                    }
                    onOrganizationQueryChange={
                        handleOrganizationQueryChange
                    }
                    onOrganizationSearch={
                        handleOrganizationSearch
                    }
                    onOrganizationSelect={(
                        organization,
                    ) => {
                        cancelOrganizationSearch()
                        setTargetOrganizationId(
                            organization.id,
                        )
                        setOrganizationQuery(
                            organization.name,
                        )
                        setSelectedOrganization({
                            id: organization.id,
                            name: organization.name,
                        })
                        setOrganizationResults([])
                    }}
                    onEdit={() => {
                        setPolicyModalOpen(true)
                    }}
                />
            </section>

            <section className="models-catalog-toolbar">
                <div className="models-catalog-toolbar__copy">
                    <h2>Каталог моделей</h2>
                    <p>
                        Последняя сохраненная версия и действующая версия могут различаться.
                        Детали и полная цепочка доступны через «История версий».
                    </p>
                    {!isSuperAdmin && (
                        <p className="models-catalog-permission-hint" role="note">
                            <strong>Каталог изменяет SUPER_ADMIN.</strong>{' '}
                            Администратор организации может просматривать версии
                            и настраивать правила своей организации.
                            «Создать модель» и «Добавить Runtime в каталог»
                            доступны только платформенному администратору.
                        </p>
                    )}
                </div>

            </section>

            <details className="models-evidence-panel">
                <summary>
                    <span className="models-evidence-panel__title">
                        <strong>Диагностика маршрутизации</strong>
                        <small>Проверить решение по ID из аудита или ошибки запроса</small>
                    </span>
                    <span className="models-evidence-panel__action" aria-hidden="true">
                        Открыть диагностику
                    </span>
                </summary>

                <p className="models-evidence-panel__hint">
                    По ID решения можно увидеть,
                    почему модель была выбрана или
                    отклонена. ID можно взять из
                    аудита или сообщения об ошибке.
                </p>

                <form
                    className="models-evidence-search"
                    onSubmit={(event) => {
                        event.preventDefault()
                        void handleRouteLookup()
                    }}
                >
                    <label>
                        ID решения
                        <input
                            type="text"
                            value={routeDecisionId}
                            placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                            autoComplete="off"
                            onChange={(event) => {
                                handleRouteDecisionIdChange(
                                    event.target.value,
                                )
                            }}
                        />
                    </label>

                    <button
                        type="submit"
                        disabled={routePending}
                    >
                        {routePending
                            ? 'Проверяем...'
                            : 'Проверить'}
                    </button>
                </form>

                {routeError && (
                    <ErrorState
                        message={routeError}
                        variant="inline"
                    />
                )}

                {routeDecision && (
                    <RouteDecisionEvidence
                        decision={routeDecision}
                    />
                )}
            </details>
        </div>
    )

}

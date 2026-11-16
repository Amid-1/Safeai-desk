// frontend/src/features/models/ui/overview/ModelsOverview.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { ModelAvailabilitySummary } from '../ModelAvailabilitySummary'
import { modelConfigurationReadiness } from '../modelConfigurationReadiness'
import type { ModelsSection } from '../modelsNavigationState'
import { runtimeHealthLabel, probeTone } from '../modelControlPlaneViews/viewPresentationLabels'
import { ModelIcon } from '../ModelIcon'
import type { ModelIconName } from '../ModelIcon'
import { StatusPill } from '../modelControlPlaneViews/viewPresentation'
import type { StatusTone } from '../modelControlPlaneViews/viewPresentationLabels'

function OverviewTile({ icon, label, value, status, tone, detail, onClick }: {
    icon: ModelIconName; label: string; value: string; status: string
    tone: StatusTone; detail: string; onClick: () => void
}) {
    return <button type="button" className="models-overview-tile" data-tone={tone} onClick={onClick}>
        <span className="models-tile-heading"><span className="models-tile-icon"><ModelIcon name={icon} /></span>{label}<ModelIcon name="arrow" /></span>
        <strong>{value}</strong>
        <StatusPill tone={tone} label={status} />
        <small>{detail}</small>
    </button>
}

export function ModelsOverview({ controller, navigate }: {
    controller: AdminModelsController
    navigate: (section: ModelsSection) => void
}) {
    const { runtime, policy, catalog, effectiveCatalog, selectedOrganizationName, setPolicyModalOpen, isSuperAdmin, runtimeProbe } = controller
    if (!runtime || !policy) return null
    const readiness = modelConfigurationReadiness(runtime, effectiveCatalog, policy)
    const healthTone: StatusTone = runtimeProbe ? probeTone(runtimeProbe.status)
        : runtime.healthStatus === 'AVAILABLE' ? 'success' : runtime.healthStatus === 'UNAVAILABLE' ? 'danger' : 'warning'
    return (
        <div className="models-section-stack models-overview-workspace">
            <div className="models-section-heading">
                <div>
                    <h2>Состояние конфигурации</h2>
                    <p>Подключение, каталог и правила выбранной организации. Доступ к конкретному запросу определяет сервер.</p>
                </div>
            </div>
            <section className="models-overview-stats" aria-label="Ключевые показатели">
                <OverviewTile icon="server" label="Подключение" value={runtime.model}
                    status={runtime.enabled ? 'Включено в настройках' : 'Отключено'} tone={runtime.enabled ? 'success' : 'danger'}
                    detail={`Провайдер: ${runtime.provider}`} onClick={() => navigate('diagnostics')} />
                <OverviewTile icon="layers" label="Каталог моделей" value={`Моделей: ${catalog.length}`}
                    status={`Действующих версий: ${effectiveCatalog.length}`} tone={readiness.compatibleCount ? 'success' : 'warning'}
                    detail="Версии и характеристики" onClick={() => navigate('catalog')} />
                <OverviewTile icon="shield" label="Правила организации" value={policy.configured ? `Версия ${policy.version}` : 'Не настроены'}
                    status={!policy.configured ? 'Требуется настройка' : policy.enabled ? 'Включены' : 'Отключены'}
                    tone={policy.configured && policy.enabled ? 'success' : 'warning'}
                    detail={selectedOrganizationName ?? 'Ваша организация'} onClick={() => navigate('access')} />
                <OverviewTile icon="activity" label="Проверка соединения" value={runtimeHealthLabel(runtime, runtimeProbe ?? null)}
                    status={runtimeProbe || runtime.healthStatus !== 'NOT_PROBED' ? 'Результат сервера' : 'Не проверено'} tone={healthTone}
                    detail={isSuperAdmin ? 'Запуск в диагностике' : 'Запуск — SUPER_ADMIN'} onClick={() => navigate('diagnostics')} />
            </section>
            {/* Security-relevant blockers remain visible when the advisory details are collapsed. */}
            {readiness.blockers.length > 0 && (
                <div className="models-overview-alert" role="status">
                    <strong>Конфигурация требует внимания</strong>
                    {readiness.blockers.map((message) => <p key={message}>{message}</p>)}
                </div>
            )}
            <details className="models-overview-readiness" open>
                <summary>Предварительная проверка совместимости <span className="models-readiness-show">Показать подробности</span><span className="models-readiness-hide">Скрыть подробности</span></summary>
                <ModelAvailabilitySummary runtime={runtime} policy={policy} effectiveCatalog={effectiveCatalog}
                    organizationName={selectedOrganizationName} onOpenPolicy={() => setPolicyModalOpen(true)} />
            </details>
            <details className="models-guide">
                <summary>Как устроено управление моделями?</summary>
                <p>Подключение определяет фактического AI-провайдера на сервере. Каталог хранит версии характеристик моделей. Правила ограничивают использование в организации. Окончательное решение о запросе принимает сервер.</p>
            </details>
        </div>
    )
}

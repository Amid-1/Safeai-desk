// frontend/src/features/models/ui/overview/ModelsOverview.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { ModelAvailabilitySummary } from '../ModelAvailabilitySummary'
import { modelConfigurationReadiness } from '../modelConfigurationReadiness'
import type { DiagnosticsTarget, ModelsSection } from '../modelsNavigationState'
import { runtimeHealthLabel, runtimeHealthTone } from '../modelControlPlaneViews/viewPresentationLabels'
import { ModelIcon } from '../ModelIcon'
import type { ModelIconName } from '../ModelIcon'
import { StatusPill } from '../modelControlPlaneViews/viewPresentation'
import type { StatusTone } from '../modelControlPlaneViews/viewPresentationLabels'

const pluralRules = new Intl.PluralRules('ru-RU')

function countLabel(count: number, one: string, few: string, many: string) {
    const form = pluralRules.select(count)
    return `${count.toLocaleString('ru-RU')} ${form === 'one' ? one : form === 'few' ? few : many}`
}

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

export function ModelsOverview({ controller, navigate, onOpenPolicy }: {
    controller: AdminModelsController
    navigate: (section: ModelsSection, target?: DiagnosticsTarget) => void
    onOpenPolicy: () => void
}) {
    const { runtime, policy, catalog, effectiveCatalog, selectedOrganizationName, isSuperAdmin, runtimeProbe } = controller
    if (!runtime || !policy) return null
    const readiness = modelConfigurationReadiness(runtime, effectiveCatalog, policy)
    const healthTone = runtimeHealthTone(runtime, runtimeProbe)
    return (
        <div className="models-section-stack models-overview-workspace">
            <div className="models-section-heading">
                <div>
                    <h2>Состояние конфигурации</h2>
                </div>
            </div>
            <section className="models-overview-stats" aria-label="Ключевые показатели">
                <OverviewTile icon="server" label="Подключение модели" value={runtime.model}
                    status={runtime.enabled ? 'Настроена' : 'Отключена'} tone="neutral"
                    detail={`Провайдер: ${runtime.provider}`} onClick={() => navigate('diagnostics', 'connection')} />
                <OverviewTile icon="layers" label="Каталог моделей" value={countLabel(catalog.length, 'модель', 'модели', 'моделей')}
                    status={countLabel(effectiveCatalog.length, 'действующая версия', 'действующие версии', 'действующих версий')} tone={readiness.compatibleCount === 1 ? 'success' : 'warning'}
                    detail="Версии и характеристики" onClick={() => navigate('catalog')} />
                <OverviewTile icon="shield" label="Правила доступа" value={policy.configured ? `Версия ${policy.version}` : 'Не настроены'}
                    status={!policy.configured ? 'Требуется настройка' : policy.enabled ? 'Включены' : 'Отключены'}
                    tone={policy.configured && policy.enabled ? 'success' : 'warning'}
                    detail={selectedOrganizationName ?? 'Ваша организация'} onClick={() => navigate('access')} />
                <OverviewTile icon="activity" label="Проверка соединения" value={runtimeHealthLabel(runtime, runtimeProbe ?? null)}
                    status={!runtime.enabled ? 'Подключение отключено' : runtimeProbe || runtime.healthStatus !== 'NOT_PROBED' ? 'Результат сервера' : 'Не проверено'} tone={healthTone}
                    detail={isSuperAdmin ? 'Перейти к проверке' : 'Проверку запускает суперадминистратор'} onClick={() => navigate('diagnostics', 'probe')} />
            </section>
            <ModelAvailabilitySummary key={policy.organizationId} runtime={runtime} policy={policy} effectiveCatalog={effectiveCatalog}
                organizationName={selectedOrganizationName} onOpenPolicy={onOpenPolicy} probe={runtimeProbe} />
        </div>
    )
}

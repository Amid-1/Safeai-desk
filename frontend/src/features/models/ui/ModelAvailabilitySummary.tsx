// frontend/src/features/models/ui/ModelAvailabilitySummary.tsx
/* ============================================================
   frontend/src/features/models/ui/ModelAvailabilitySummary.tsx
   ============================================================ */
import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
    RuntimeModelProbe,
} from '../../../entities/model/api/modelApi'

import { modelConfigurationReadiness } from './modelConfigurationReadiness'
import { ModelIcon } from './ModelIcon'
import type { ModelIconName } from './ModelIcon'
import { StatusPill } from './modelControlPlaneViews/viewPresentation'
import type { StatusTone } from './modelControlPlaneViews/viewPresentationLabels'
import { runtimeHealthLabel, runtimeHealthTone } from './modelControlPlaneViews/viewPresentationLabels'

export function ModelAvailabilitySummary({
    runtime,
    effectiveCatalog,
    policy,
    organizationName,
    onOpenPolicy,
    probe = null,
}: {
    runtime: RuntimeModelStatus
    effectiveCatalog: ModelCatalogEntry[]
    policy: OrganizationModelPolicy
    organizationName: string | null
    onOpenPolicy: () => void
    probe?: RuntimeModelProbe | null
}) {
    const result = modelConfigurationReadiness(runtime, effectiveCatalog, policy, probe)
    const multipleMatches = result.compatibleCount > 1
    const hasWarnings = result.blockers.length > 0 || multipleMatches
    const policyExcludesMatches = result.compatibleCount > 0 && policy.enabled && result.allowedCount === 0
    const steps: { icon: ModelIconName; title: string; description: string; status: string; tone: StatusTone }[] = [
        { icon: 'server', title: 'Подключение', description: `${runtime.provider} / ${runtime.model}`,
            status: runtime.enabled ? 'Настроена' : 'Отключена', tone: 'neutral' },
        { icon: 'activity', title: 'Соединение', description: 'Последняя фактическая проверка, не проверка правил',
            status: runtimeHealthLabel(runtime, probe), tone: runtimeHealthTone(runtime, probe) },
        { icon: 'layers', title: 'Каталог версий', description: `Совпадающих действующих версий: ${result.compatibleCount}`,
            status: multipleMatches ? 'Несколько совпадений' : result.compatibleCount ? 'Совпадение найдено' : 'Нет совпадения',
            tone: multipleMatches ? 'warning' : result.compatibleCount ? 'success' : 'danger' },
        { icon: 'shield', title: 'Правила организации',
            description: `${organizationName ?? 'Выбранная организация'} · ${policy.configured ? `версия ${policy.version}` : 'нет версии'}`,
            status: !policy.configured ? 'Не настроены' : !policy.enabled ? 'Отключены' : policyExcludesMatches ? 'Исключают модель' : 'Включены',
            tone: !policy.configured || policyExcludesMatches ? 'danger' : 'neutral' },
        { icon: 'activity', title: 'Итоговое решение', description: 'Определяется сервером для конкретного запроса',
            status: 'При выполнении запроса', tone: 'warning' },
    ]

    return (
        <section className="models-readiness" aria-labelledby="models-readiness-heading">
            <div className="models-readiness__heading">
                <div>
                    <h2 id="models-readiness-heading">Предварительная проверка совместимости</h2>
                    <p>
                        {hasWarnings
                            ? 'Обнаружены условия, которые могут препятствовать запросам.'
                            : 'Явных расхождений не обнаружено. Окончательный доступ определяется сервером.'}
                    </p>
                </div>
                <StatusPill tone="warning" label={hasWarnings ? 'Требует внимания' : 'Требуется проверка запроса'} />
            </div>
            {hasWarnings && <div className="models-overview-alert" role="status">
                {result.blockers.map(message => <p key={message}>{message}</p>)}
                {multipleMatches && <p>Найдено несколько совпадающих записей каталога. Выбор зависит от правил и конкретного запроса.</p>}
            </div>}
            <div className="models-readiness-actions">
                <span>Бюджет, стоимость, возможности и защита данных проверяются сервером.</span>
                <button type="button" className="secondary-button" onClick={onOpenPolicy}>
                    Открыть проверку правил
                </button>
            </div>
            <details className="models-overview-readiness models-inline-details">
                <summary><span className="models-readiness-show">Показать подробности</span><span className="models-readiness-hide">Скрыть подробности</span></summary>
                <ol className="models-readiness-timeline">
                    {steps.map((step, index) => <li key={step.title} data-tone={step.tone}>
                        <span className="models-timeline-icon"><ModelIcon name={step.icon} /></span>
                        <div><strong>{index + 1}. {step.title}</strong><small>{step.description}</small></div>
                        <StatusPill tone={step.tone} label={step.status} />
                    </li>)}
                </ol>
            </details>
        </section>
    )
}

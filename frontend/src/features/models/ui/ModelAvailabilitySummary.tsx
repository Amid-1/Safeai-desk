// frontend/src/features/models/ui/ModelAvailabilitySummary.tsx
/* ============================================================
   frontend/src/features/models/ui/ModelAvailabilitySummary.tsx
   ============================================================ */
import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../entities/model/api/modelApi'

import { modelConfigurationReadiness } from './modelConfigurationReadiness'
import { ModelIcon } from './ModelIcon'
import type { ModelIconName } from './ModelIcon'
import { StatusPill } from './modelControlPlaneViews/viewPresentation'
import type { StatusTone } from './modelControlPlaneViews/viewPresentationLabels'

export function ModelAvailabilitySummary({
    runtime,
    effectiveCatalog,
    policy,
    organizationName,
    onOpenPolicy,
}: {
    runtime: RuntimeModelStatus
    effectiveCatalog: ModelCatalogEntry[]
    policy: OrganizationModelPolicy
    organizationName: string | null
    onOpenPolicy: () => void
}) {
    const result = modelConfigurationReadiness(runtime, effectiveCatalog, policy)
    const hasWarnings = result.blockers.length > 0
    const policyExcludesMatches = result.compatibleCount > 0 && policy.enabled && result.allowedCount === 0
    const steps: { icon: ModelIconName; title: string; description: string; status: string; tone: StatusTone }[] = [
        { icon: 'server', title: 'Подключение', description: `${runtime.provider} / ${runtime.model}`,
            status: runtime.enabled ? 'Включено в настройках' : 'Отключено', tone: runtime.enabled ? 'success' : 'danger' },
        { icon: 'layers', title: 'Каталог версий', description: `Совпадающих действующих версий: ${result.compatibleCount}`,
            status: result.compatibleCount ? 'Совпадение найдено' : 'Нет совпадения', tone: result.compatibleCount ? 'success' : 'danger' },
        { icon: 'shield', title: 'Правила организации',
            description: `${organizationName ?? 'Выбранная организация'} · ${policy.configured ? `версия ${policy.version}` : 'нет версии'}`,
            status: !policy.configured ? 'Не настроены' : !policy.enabled ? 'Отключены' : policyExcludesMatches ? 'Исключают модель' : 'Включены',
            tone: !policy.configured || policyExcludesMatches ? 'danger' : policy.enabled ? 'success' : 'neutral' },
        { icon: 'activity', title: 'Итоговое решение', description: 'Определяется сервером для конкретного запроса',
            status: 'Требуется проверка запроса', tone: 'warning' },
    ]

    return (
        <section className="models-readiness" aria-labelledby="models-readiness-heading">
            <div className="models-readiness__heading">
                <div>
                    <span className="models-readiness__eyebrow">ПРОВЕРКА КОНФИГУРАЦИИ</span>
                    <h2 id="models-readiness-heading">Совместимость конфигурации</h2>
                    <p>
                        {hasWarnings
                            ? 'Есть условия, которые могут препятствовать выполнению запросов.'
                            : 'Явных расхождений между подключённой моделью и каталогом не обнаружено. Окончательный доступ определяется сервером для каждого запроса.'}
                    </p>
                </div>
                <span className={`models-readiness__status ${hasWarnings ? 'models-readiness__status--warning' : ''}`}>
                    {hasWarnings ? 'Требует внимания' : 'Нужна проверка запроса'}
                </span>
            </div>
            <ol className="models-readiness-timeline">
                {steps.map((step, index) => <li key={step.title} data-tone={step.tone}>
                    <span className="models-timeline-icon"><ModelIcon name={step.icon} /></span>
                    <div><strong>{index + 1}. {step.title}</strong><small>{step.description}</small></div>
                    <StatusPill tone={step.tone} label={step.status} />
                </li>)}
            </ol>
            <div className="models-readiness__footer">
                <span>
                    Это предварительная проверка конфигурации. Стоимость, бюджет, возможности
                        и условия обработки данных проверяются отдельно. Серверная проверка
                        правил оценивает конфигурацию; фактическое решение сохраняется при обработке запроса.
                </span>
                <button type="button" className="secondary-button" onClick={onOpenPolicy}>
                    Открыть проверку правил
                </button>
            </div>
        </section>
    )
}

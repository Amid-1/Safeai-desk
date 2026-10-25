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

    return (
        <section className="models-readiness" aria-labelledby="models-readiness-heading">
            <div className="models-readiness__heading">
                <div>
                    <span className="models-readiness__eyebrow">ПРОВЕРКА КОНФИГУРАЦИИ</span>
                    <h2 id="models-readiness-heading">Проверка доступности модели</h2>
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
            <div className="models-readiness__steps">
                <div>
                    <small>1 · Подключение</small>
                    <strong>{runtime.enabled ? 'Модель включена в настройках' : 'Модель отключена'}</strong>
                    <span>{runtime.provider} / {runtime.model}</span>
                </div>
                <div>
                    <small>2 · Каталог версий</small>
                    <strong>{result.compatibleCount > 0
                        ? `Совпадающих действующих версий: ${result.compatibleCount}`
                        : 'Совпадающих версий нет'}</strong>
                    <span>Учитываются действующие версии каталога, а не только последние сохранённые.</span>
                </div>
                <div>
                    <small>3 · Правила доступа</small>
                    <strong>{!policy.configured ? 'Не настроены' : policy.enabled ? `Включены · версия ${policy.version}` : 'Отключены'}</strong>
                    <span>{organizationName ?? 'Выбранная организация'}</span>
                </div>
            </div>
            {hasWarnings && (
                <div className="models-readiness__warnings" role="status">
                    {result.blockers.map((message) => <p key={message}>{message}</p>)}
                </div>
            )}
            <div className="models-readiness__footer">
                <span>
                    Это предварительная проверка конфигурации. Стоимость, бюджет, возможности
                    и условия обработки данных проверяются отдельно. Для окончательного
                    решения используйте серверную проверку правил или диагностику ниже.
                </span>
                <button type="button" className="secondary-button" onClick={onOpenPolicy}>
                    Открыть проверку правил
                </button>
            </div>
        </section>
    )
}

// frontend/src/features/models/ui/overview/ModelsOverview.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { ModelAvailabilitySummary } from '../ModelAvailabilitySummary'
import { modelConfigurationReadiness } from '../modelConfigurationReadiness'
import type { ModelsSection } from '../modelsNavigationState'

export function ModelsOverview({ controller, navigate }: {
    controller: AdminModelsController
    navigate: (section: ModelsSection) => void
}) {
    const { runtime, policy, catalog, effectiveCatalog, selectedOrganizationName, setPolicyModalOpen } = controller
    if (!runtime || !policy) return null
    const readiness = modelConfigurationReadiness(runtime, effectiveCatalog, policy)
    return (
        <div className="models-section-stack models-overview-workspace">
            <div className="models-section-heading">
                <div>
                    <span className="models-eyebrow">ОБЩАЯ КАРТИНА</span>
                    <h2>Состояние моделей</h2>
                    <p>Здесь отображается состояние конфигурации, а не гарантия выполнения конкретного AI-запроса.</p>
                </div>
            </div>
            <section className="models-overview-stats" aria-label="Ключевые показатели">
                <div><span>Подключённая модель</span><strong>{runtime.model}</strong><small>{runtime.enabled ? 'Включена' : 'Отключена'}</small></div>
                <div><span>Каталог</span><strong>{effectiveCatalog.length} действующих версий</strong><small>Всего сохранённых записей: {catalog.length}</small></div>
                <div><span>Правила доступа</span><strong>{policy.configured ? `Версия ${policy.version}` : 'Не настроены'}</strong><small>{selectedOrganizationName ?? policy.organizationId}</small></div>
                <div><span>Проверка соединения</span><strong>{runtime.healthStatus === 'AVAILABLE' ? 'Доступна' : runtime.healthStatus === 'UNAVAILABLE' ? 'Недоступна' : 'Не подтверждена'}</strong><small>Проверка запускается отдельно</small></div>
            </section>
            {/* Security-relevant blockers remain visible when the advisory details are collapsed. */}
            {readiness.blockers.length > 0 && (
                <div className="models-overview-alert" role="status">
                    <strong>Конфигурация требует внимания</strong>
                    {readiness.blockers.map((message) => <p key={message}>{message}</p>)}
                </div>
            )}
            <details className="models-overview-readiness">
                <summary>Предварительная проверка совместимости <span>Показать подробности</span></summary>
                <ModelAvailabilitySummary runtime={runtime} policy={policy} effectiveCatalog={effectiveCatalog}
                    organizationName={selectedOrganizationName} onOpenPolicy={() => setPolicyModalOpen(true)} />
            </details>
            <div className="models-section-links" aria-label="Перейти к управлению">
                <button type="button" className="secondary-button" onClick={() => navigate('catalog')}>Каталог моделей</button>
                <button type="button" className="secondary-button" onClick={() => navigate('access')}>Доступ организаций</button>
                <button type="button" className="secondary-button" onClick={() => navigate('diagnostics')}>Проверки и диагностика</button>
            </div>
            <details className="models-guide">
                <summary>Как устроено управление моделями?</summary>
                <p>Подключение определяет фактического AI-провайдера на сервере. Каталог хранит версии характеристик моделей. Правила ограничивают использование в организации. Окончательное решение о запросе принимает сервер.</p>
            </details>
        </div>
    )
}

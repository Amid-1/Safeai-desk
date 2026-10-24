// frontend/src/features/models/ui/overview/ModelsOverview.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { ModelAvailabilitySummary } from '../ModelAvailabilitySummary'
import type { ModelsSection } from '../AdminModelsNavigation'

export function ModelsOverview({ controller, navigate }: {
    controller: AdminModelsController
    navigate: (section: ModelsSection) => void
}) {
    const { runtime, policy, catalog, effectiveCatalog, selectedOrganizationName, setPolicyModalOpen } = controller
    if (!runtime || !policy) return null
    return (
        <div className="models-section-stack">
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
            <ModelAvailabilitySummary runtime={runtime} policy={policy} effectiveCatalog={effectiveCatalog}
                organizationName={selectedOrganizationName} onOpenPolicy={() => setPolicyModalOpen(true)} />
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

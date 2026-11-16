// frontend/src/features/models/ui/AdminModelsUpper.tsx
// Область заголовка. Действия вынесены в соответствующие разделы.
import type { RuntimeModelStatus } from '../../../entities/model/api/modelApi'

export function AdminModelsUpper({ runtime }: { runtime: RuntimeModelStatus }) {
    return (
        <header className="models-hero models-sections-hero">
            <div className="models-hero__content">
                <span className="models-eyebrow">МОДЕЛИ И ПОЛИТИКИ ДОСТУПА</span>
                <h1>Управление AI</h1>
                <p>Каталог моделей, правила организаций и диагностика запросов.</p>
            </div>
            <div className="models-runtime-badge" aria-label="Подключённая модель">
                <span>Подключение на сервере</span>
                <strong>{runtime.provider} · {runtime.model}</strong>
                <small className={runtime.enabled ? 'models-runtime-state is-enabled' : 'models-runtime-state is-disabled'}>
                    {runtime.enabled ? 'Включена в настройках' : 'Отключена в настройках'}
                    {runtime.provider === 'mock' && ' · Тестовая модель'}
                </small>
            </div>
        </header>
    )
}

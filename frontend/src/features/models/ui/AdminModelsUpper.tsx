// frontend/src/features/models/ui/AdminModelsUpper.tsx
// Область заголовка. Действия вынесены в соответствующие разделы.
import type { RuntimeModelStatus } from '../../../entities/model/api/modelApi'

export function AdminModelsUpper({ runtime }: { runtime: RuntimeModelStatus }) {
    return (
        <header className="models-hero models-sections-hero">
            <div className="models-hero__content">
                <span className="models-eyebrow">SAFEAI DESK / УПРАВЛЕНИЕ</span>
                <h1>Модели и доступ</h1>
                <p>Подключение, каталог, правила организаций и диагностика.</p>
            </div>
            <div className="models-runtime-badge" aria-label="Подключённая модель">
                <span>Сейчас настроена модель</span>
                <strong>{runtime.provider} · {runtime.model}</strong>
                <small>{runtime.enabled ? 'Включена в настройках' : 'Отключена в настройках'}</small>
            </div>
        </header>
    )
}

// frontend/src/features/models/ui/AdminModelsUpper.tsx
// Область заголовка. Действия вынесены в соответствующие разделы.
import type { RuntimeModelStatus } from '../../../entities/model/api/modelApi'
import { AdminPageHeader } from '../../../shared/ui/AdminPageHeader'

export function AdminModelsUpper({ runtime }: { runtime: RuntimeModelStatus }) {
    return (
        <AdminPageHeader className="models-hero models-sections-hero">
            <div className="models-hero__content">
                <h1>Управление AI-моделями</h1>
                <p>Каталог, правила доступа и диагностика</p>
            </div>
            <div className="models-runtime-badge" aria-label="Подключённая модель">
                <span>Подключение на сервере</span>
                <strong>{runtime.provider} · {runtime.model}</strong>
                <small className={runtime.enabled ? 'models-runtime-state' : 'models-runtime-state is-disabled'}>
                    {runtime.enabled ? 'Настроена · доступность проверяется отдельно' : 'Отключена в настройках'}
                    {runtime.provider === 'mock' && ' · Тестовая модель'}
                </small>
            </div>
        </AdminPageHeader>
    )
}

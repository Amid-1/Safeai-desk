// frontend/src/features/models/ui/modelsNavigationState.ts
export type ModelsSection = 'overview' | 'catalog' | 'access' | 'diagnostics'

export const MODELS_SECTIONS: ReadonlyArray<{ id: ModelsSection; label: string; description: string }> = [
    { id: 'overview', label: 'Обзор', description: 'Текущее состояние подключения и предварительная совместимость' },
    { id: 'catalog', label: 'Каталог', description: 'Сохранённые версии и история моделей' },
    { id: 'access', label: 'Доступ', description: 'Правила и ограничения организаций' },
    { id: 'diagnostics', label: 'Диагностика', description: 'Проверка соединения и решений сервера' },
]

export function parseModelsSection(value: string | null): ModelsSection {
    return MODELS_SECTIONS.find((item) => item.id === value)?.id ?? 'overview'
}

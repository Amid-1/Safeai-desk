// frontend/src/features/models/ui/AdminModelsNavigation.tsx
import type { KeyboardEvent } from 'react'

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

export function AdminModelsNavigation({ section, onSelect }: {
    section: ModelsSection
    onSelect: (value: ModelsSection) => void
}) {
    function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, current: ModelsSection) {
        const index = MODELS_SECTIONS.findIndex((item) => item.id === current)
        const nextIndex = event.key === 'ArrowRight'
            ? (index + 1) % MODELS_SECTIONS.length
            : event.key === 'ArrowLeft'
                ? (index - 1 + MODELS_SECTIONS.length) % MODELS_SECTIONS.length
                : event.key === 'Home' ? 0
                    : event.key === 'End' ? MODELS_SECTIONS.length - 1 : -1
        if (nextIndex < 0) return
        event.preventDefault()
        const target = MODELS_SECTIONS[nextIndex]
        if (!target) return
        onSelect(target.id)
        const tabs = event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')
        tabs?.[nextIndex]?.focus()
    }

    return (
        <nav aria-label="Разделы управления моделями" className="models-sections-nav">
            <div className="models-sections-nav__tabs" role="tablist" aria-label="Модели и доступ">
                {MODELS_SECTIONS.map((item) => (
                    <button key={item.id} type="button" role="tab"
                        id={`models-tab-${item.id}`}
                        aria-controls="models-section-panel"
                        aria-selected={section === item.id}
                        tabIndex={section === item.id ? 0 : -1}
                        className={section === item.id ? 'models-sections-nav__tab is-active' : 'models-sections-nav__tab'}
                        onClick={() => onSelect(item.id)}
                        onKeyDown={(event) => handleKeyDown(event, item.id)}>
                        {item.label}
                    </button>
                ))}
            </div>
            <p className="models-sections-nav__description">
                {MODELS_SECTIONS.find((item) => item.id === section)?.description}
            </p>
        </nav>
    )
}

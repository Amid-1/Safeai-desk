// frontend/src/features/models/ui/catalogVersion/CatalogFormTabs.tsx
import {useRef} from 'react'
import type {KeyboardEvent, ReactNode} from 'react'
import type {CatalogSection} from '../modelControlPlaneSupport'

const TABS: readonly {id: CatalogSection; label: string}[] = [
    {id: 'identity', label: 'Основные данные'},
    {id: 'limits', label: 'Лимиты'},
    {id: 'pricing', label: 'Тарификация'},
    {id: 'data', label: 'Безопасность данных'},
    {id: 'capabilities', label: 'Возможности модели'},
]

type Props = {
    formId: string
    selected: CatalogSection
    onSelect: (section: CatalogSection) => void
    panels: Record<CatalogSection, ReactNode>
}

export function CatalogFormTabs({formId, selected, onSelect, panels}: Props) {
    const buttons = useRef<Partial<Record<CatalogSection, HTMLButtonElement | null>>>({})

    function navigate(event: KeyboardEvent<HTMLButtonElement>, section: CatalogSection) {
        const index = TABS.findIndex(tab => tab.id === section)
        const next = event.key === 'ArrowRight' ? (index + 1) % TABS.length
            : event.key === 'ArrowLeft' ? (index + TABS.length - 1) % TABS.length
                : event.key === 'Home' ? 0 : event.key === 'End' ? TABS.length - 1 : -1
        if (next < 0) return
        event.preventDefault()
        const id = TABS[next]!.id
        onSelect(id)
        buttons.current[id]?.focus()
    }

    return <div className="models-catalog-tabs">
        <div className="models-catalog-tabs__nav" role="tablist" aria-label="Разделы каталога модели">
            {TABS.map(({id, label}) => <button key={id} type="button" role="tab"
                ref={element => { buttons.current[id] = element }}
                id={`${formId}-tab-${id}`}
                aria-controls={`${formId}-panel-${id}`}
                aria-selected={selected === id} tabIndex={selected === id ? 0 : -1}
                onClick={() => onSelect(id)} onKeyDown={event => navigate(event, id)}>
                {label}
            </button>)}
        </div>
        {TABS.map(({id}) => <div key={id} role="tabpanel"
            id={`${formId}-panel-${id}`}
            aria-labelledby={`${formId}-tab-${id}`}
            hidden={selected !== id} inert={selected !== id} tabIndex={0}>
            {panels[id]}
        </div>)}
    </div>
}

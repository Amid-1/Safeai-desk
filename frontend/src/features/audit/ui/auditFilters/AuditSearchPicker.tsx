// frontend/src/features/audit/ui/auditFilters/AuditSearchPicker.tsx
import { useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

type Option = { value: string; label: string; group?: string }

/** Typing searches suggestions; only selecting changes the server filter. */
export function AuditSearchPicker({ label, placeholder, query, onQueryChange, value, options,
    onSelect, disabled, pending = false, allLabel, maxLength = 320 }: {
    label: string; placeholder: string; query: string; onQueryChange: (query: string) => void
    value: string; options: readonly Option[]; onSelect: (value: string) => void
    disabled: boolean; pending?: boolean; allLabel: string; maxLength?: number
}) {
    const id = useId()
    const [open, setOpen] = useState(false)
    const [active, setActive] = useState(-1)
    const anchorRef = useRef<HTMLDivElement>(null)
    const resultsRef = useRef<HTMLDivElement>(null)
    const expanded = open && !disabled
    const selected = options.find((option) => option.value === value)
    const items: Option[] = [{ value: '', label: allLabel }, ...options]
    const activeItem = expanded && !pending ? items[active] : undefined

    // The upper audit pane clips overflow. Anchor a body-level overlay instead
    // of growing the form or being clipped by the split-table boundary.
    useLayoutEffect(() => {
        const anchor = anchorRef.current
        const results = resultsRef.current
        if (!expanded || !anchor || !results) return
        const upper = anchor.closest('.audit-page__upper')
        const split = anchor.closest('.resizable-scroll-region__upper')
        const gap = 5
        const edge = 8
        const maxHeight = 230

        function reposition() {
            if (!anchor || !results) return
            const rect = anchor.getBoundingClientRect()
            const viewportWidth = document.documentElement.clientWidth || window.innerWidth
            const viewportHeight = window.innerHeight
            const paneBounds = [upper, split].filter((pane) => pane !== null).map((pane) => pane.getBoundingClientRect())
            const visibleTop = Math.max(edge, ...paneBounds.map((bounds) => bounds.top))
            const visibleBottom = Math.min(viewportHeight - edge, ...paneBounds.map((bounds) => bounds.bottom))
            const below = Math.max(0, viewportHeight - rect.bottom - gap - edge)
            const above = Math.max(0, rect.top - visibleTop - gap)
            const opensAbove = below < maxHeight && above > below
            const width = Math.min(rect.width, viewportWidth - 2 * edge)
            const left = Math.max(edge, Math.min(rect.left, viewportWidth - edge - width))
            Object.assign(results.style, {
                left: `${left}px`, width: `${width}px`,
                top: opensAbove ? 'auto' : `${rect.bottom + gap}px`,
                bottom: opensAbove ? `${viewportHeight - rect.top + gap}px` : 'auto',
                maxHeight: `${Math.min(maxHeight, opensAbove ? above : below)}px`,
                visibility: rect.width > 0 && (rect.bottom <= visibleTop || rect.top >= visibleBottom) ? 'hidden' : 'visible',
            })
        }

        reposition()
        window.addEventListener('resize', reposition)
        window.addEventListener('scroll', reposition, true)
        const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(reposition) : null
        observer?.observe(anchor)
        if (upper) observer?.observe(upper)
        if (split) observer?.observe(split)
        return () => {
            window.removeEventListener('resize', reposition)
            window.removeEventListener('scroll', reposition, true)
            observer?.disconnect()
        }
    }, [expanded])

    useLayoutEffect(() => {
        const results = resultsRef.current
        const item = activeItem && document.getElementById(id + '-option-' + active)
        if (!results || !item) return
        const bounds = results.getBoundingClientRect()
        const option = item.getBoundingClientRect()
        if (option.bottom > bounds.bottom) results.scrollTop += option.bottom - bounds.bottom
        else if (option.top < bounds.top) results.scrollTop -= bounds.top - option.top
    }, [active, activeItem, id])
    function choose(next: string) {
        onSelect(next)
        onQueryChange('')
        setOpen(false)
        setActive(-1)
    }
    return <div className="audit-search-picker">
        <label htmlFor={id}>{label}</label>
        <div className="audit-search-picker__input" ref={anchorRef}>
            <input id={id} role="combobox" type="text" autoComplete="off"
                aria-autocomplete="list" aria-expanded={expanded}
                aria-controls={expanded ? id + '-options' : undefined}
                aria-activedescendant={activeItem ? id + '-option-' + active : undefined}
                value={open ? query : (value ? selected?.label ?? value : '')}
                title={!open && value ? selected?.label ?? value : undefined}
                placeholder={placeholder} disabled={disabled} maxLength={maxLength}
                onFocus={() => { setOpen(true); setActive(-1) }}
                onClick={() => setOpen(true)}
                onBlur={() => { setOpen(false); setActive(-1) }}
                onChange={(event) => { onQueryChange(event.target.value); setOpen(true); setActive(-1) }}
                onKeyDown={(event) => {
                    if (event.key === 'Escape') {
                        event.preventDefault(); event.stopPropagation()
                        setOpen(false); setActive(-1); onQueryChange('')
                    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                        event.preventDefault(); setOpen(true)
                        if (!pending) setActive((index) => event.key === 'ArrowDown'
                            ? Math.min(index + 1, items.length - 1) : Math.max(index - 1, 0))
                    } else if (event.key === 'Enter' && activeItem) {
                        event.preventDefault(); choose(activeItem.value)
                    }
                }} />
            {value && <button type="button" className="secondary-button audit-picker-clear"
                aria-label={'Сбросить: ' + label} disabled={disabled} onClick={() => choose('')}>×</button>}
        </div>
        {expanded && createPortal(<div className="audit-search-picker__results" ref={resultsRef}>
            {pending && <p role="status">Обновляем список…</p>}
            {!pending && options.length === 0 && <p role="status">Совпадений нет. Попробуйте другой запрос.</p>}
            <div id={id + '-options'} role="listbox" aria-label={label}>
                {!pending && items.map((item, index) => <button key={item.value} type="button" role="option"
                    id={id + '-option-' + index} aria-selected={item.value === value} tabIndex={-1}
                    className={index === active ? 'is-active' : ''}
                    onMouseDown={(event) => event.preventDefault()} onClick={() => choose(item.value)}>
                    {item.group && <small className={items[index - 1]?.group === item.group ? 'audit-group-repeated' : undefined}>{item.group}</small>}<span>{item.label}</span>
                </button>)}
            </div>
        </div>, document.body)}
    </div>
}

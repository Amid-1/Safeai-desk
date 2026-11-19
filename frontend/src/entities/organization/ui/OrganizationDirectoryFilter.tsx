// frontend/src/entities/organization/ui/OrganizationDirectoryFilter.tsx
import { useEffect, useId, useState } from 'react'
import { SearchableSelect } from '../../../shared/ui/SearchableSelect'
import { searchOrganizationDirectory, getOrganizationDetails } from '../api/organizationApi'
import type { OrganizationDirectoryItem } from '../api/organizationApi'
import './OrganizationDirectoryFilter.css'

/** Server-backed selector. Disabled and platform organizations remain searchable. */
export function OrganizationDirectoryFilter({ value, onChange, disabled }: {
    value: string
    onChange: (id: string) => void
    disabled: boolean
}) {
    const [query, setQuery] = useState('')
    const id = useId()
    const [options, setOptions] = useState<OrganizationDirectoryItem[]>([])
    const [selected, setSelected] = useState<OrganizationDirectoryItem | null>(null)
    const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
    useEffect(() => {
        const controller = new AbortController()
        const timer = window.setTimeout(() => {
            setStatus('loading')
            void searchOrganizationDirectory(query, 50, {signal: controller.signal})
                .then(rows => { if (!controller.signal.aborted) {setOptions(rows); setStatus('ready')} })
                .catch(() => { if (!controller.signal.aborted) {setOptions([]); setStatus('error')} })
        }, 250)
        return () => {window.clearTimeout(timer); controller.abort()}
    }, [query])
    useEffect(() => {
        if (!value) return
        const controller = new AbortController()
        void getOrganizationDetails(value, {signal: controller.signal})
            .then(row => { if (!controller.signal.aborted) setSelected(row) })
            .catch(() => { if (!controller.signal.aborted) setSelected(null) })
        return () => controller.abort()
    }, [value])
    const rows = selected?.id === value && !options.some(row => row.id === value)
        ? [selected, ...options] : options
    const choices = [{ value: '', label: 'Все организации' }, ...rows.map(row => ({
        value: row.id, label: row.name + (row.enabled ? '' : ' (отключена)'),
    }))]
    const chosen = choices.find(row => row.value === value)
    return <div className="organization-searchable-filter">
        <SearchableSelect id={id} label="Организация" value={value} options={choices}
            selectedLabel={chosen?.label ?? 'Выбранная организация'}
            placeholder="Все организации" searchPlaceholder="Название организации"
            emptyMessage="Организации не найдены" disabled={disabled}
            loading={status === 'loading'} filterLocally={false}
            error={status === 'error' ? 'Не удалось загрузить каталог. Измените поиск.' : ''}
            helperText={options.length === 50 ? 'Уточните название для поиска других организаций.' : undefined}
            onQueryChange={next => {
                if (next !== query) { setStatus('loading'); setQuery(next) }
            }}
            onChange={id => onChange(id)} />
    </div>
}

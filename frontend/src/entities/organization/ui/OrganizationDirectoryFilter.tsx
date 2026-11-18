import { useEffect, useState } from 'react'
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
    return <div className="organization-directory-filter">
        <label htmlFor="directory-organization-search">Поиск организации</label>
        <input id="directory-organization-search" type="search" maxLength={255}
            placeholder="Название организации" value={query} disabled={disabled}
            onChange={event => setQuery(event.target.value)} />
        <label htmlFor="directory-organization-value">Организация</label>
        <select id="directory-organization-value" value={value} disabled={disabled}
            onChange={event => onChange(event.target.value)}>
            <option value="">Все организации</option>
            {value && !rows.some(row => row.id === value) && <option value={value}>Выбранная организация</option>}
            {rows.map(row => <option key={row.id} value={row.id}>{row.name}{row.enabled ? '' : ' (отключена)'}</option>)}
        </select>
        <span role="status">{status === 'error' ? 'Не удалось загрузить каталог. Измените поиск.'
            : status === 'loading' ? 'Поиск организаций…' : options.length === 50 ? 'Уточните название для поиска других организаций.' : ''}</span>
    </div>
}

// frontend/src/features/admin-organizations/ui/AdminOrganizationsView.tsx
import { useEffect, useRef, useState } from 'react'
import type { Organization } from '../../../entities/organization/api/organizationApi'
import Modal from '../../../shared/ui/Modal'
import { formatDateTime } from '../../../shared/lib/format'
import ResizableScrollRegion from '../../../shared/ui/ResizableScrollRegion'
import { AdminPageHeader } from '../../../shared/ui/AdminPageHeader'
import { EmptyState, ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { canMutateOrganization, getProtectionLabel } from '../lib/adminOrganizationsSupport'
import {
    OrganizationStatusBadge, OrganizationTypeBadge,
} from './AdminOrganizationsUi'
import { OrganizationActionsMenu } from './OrganizationActionsMenu'

const COLUMN_HELP = {
    type: {
        title: 'Тип организации',
        body: 'Клиентская — организация со своими пользователями и настройками. Системная — служебная организация SafeAI Desk, необходимая для работы платформы.',
    },
    status: {
        title: 'Статус организации',
        body: 'Включена — организация не блокирует вход своих включённых пользователей в SafeAI Desk. Отключена — вход и использование сервиса этой организацией заблокированы. Для клиентской организации нажмите «Отключить» или «Включить» под статусом: откроется подтверждение. Статус не означает, что пользователь сейчас в сети.',
    },
} as const

type ColumnKey = keyof typeof COLUMN_HELP

function ColumnHelpButton({ column, label, onOpen }: {
    column: ColumnKey
    label: string
    onOpen: (column: ColumnKey) => void
}) {
    return <button type="button" className="org-th-help org-th-help--button"
                   title={COLUMN_HELP[column].body}
                   aria-label={`Справка: ${label}`}
                   onClick={() => onOpen(column)}>?</button>
}

type AdminOrganizationsViewProps = {
    organizations: Organization[]
    loadError: string
    detailsError?: string
    mutationError: string
    createError: string
    success: string
    loading: boolean
    creating: boolean
    hasPendingAction: boolean
    page: number
    totalPages: number
    totalOrganizations: number | null
    totalMatched?: number | null
    query?: string
    type?: string
    status?: string
    onFiltersChange?: (query: string, type: string, status: string, replace?: boolean) => void
    name: string
    detailsLoadingId: string | null
    impactLoadingId: string | null
    onNameChange: (value: string) => void
    onCreate: () => Promise<boolean>
    onReload: () => void
    onDetails: (organization: Organization) => Promise<void>
    onRename: (organization: Organization) => void
    onDisable: (organization: Organization) => Promise<void>
    onEnable: (organization: Organization) => void
    onPageChange: (page: number) => void
}

/** Keep the protected PLATFORM row read-only; all mutations use existing confirmations. */
export function AdminOrganizationsView({
    organizations, loadError, detailsError, mutationError, createError, success,
    loading, creating, hasPendingAction, page, totalPages, totalOrganizations, name,
    detailsLoadingId, impactLoadingId, onNameChange, onCreate,
    onReload, onDetails, onRename, onDisable, onEnable, onPageChange,
    totalMatched, query = '', type = '', status = '', onFiltersChange,
}: AdminOrganizationsViewProps) {
    const [helpColumn, setHelpColumn] = useState<ColumnKey | null>(null)
    const [createOpen, setCreateOpen] = useState(false)
    const hasRows = !loading && !loadError && organizations.length > 0
    const pageCount = Math.max(1, totalPages)
    const visibleTotal = loading || loadError ? null : totalOrganizations
    const [searchDraft, setSearchDraft] = useState(query)
    useEffect(() => {setSearchDraft(query)}, [query])
    const filterCallback = useRef(onFiltersChange)
    const searchTimer = useRef<number | undefined>(undefined)
    useEffect(() => {filterCallback.current = onFiltersChange}, [onFiltersChange])
    useEffect(() => {
        if (searchDraft.trim() === query) return
        const timer = window.setTimeout(() => filterCallback.current?.(searchDraft.trim(), type, status, true), 300)
        searchTimer.current = timer
        return () => window.clearTimeout(timer)
    }, [searchDraft, query, type, status])
    function applyFilters(nextQuery: string, nextType: string, nextStatus: string) {
        window.clearTimeout(searchTimer.current)
        onFiltersChange?.(nextQuery.trim(), nextType, nextStatus)
    }

    function closeCreate() {
        if (creating) return
        setCreateOpen(false)
        onNameChange('')
    }

    const upper = (
        <div className="organizations-page__upper">
            <AdminPageHeader className="organizations-page__header">
                <div>
                    <h1>Организации</h1>
                    <p>Управление клиентами и их доступом к SafeAI Desk.</p>
                </div>
                <div className="organizations-header-actions">
                    <div className="organizations-page__summary" aria-label="Всего организаций">
                        <span>Всего организаций</span>
                        <strong>{visibleTotal ?? '—'}</strong>
                    </div>
                    <button type="button" disabled={creating || hasPendingAction || detailsLoadingId !== null}
                        onClick={() => setCreateOpen(true)}>＋ Создать организацию</button>
                </div>
            </AdminPageHeader>

            {mutationError && <div className="error" role="alert" aria-live="assertive">{mutationError}</div>}
            {success && <div className="success" role="status" aria-live="polite">{success}</div>}
            {detailsError && <p className="error" role="alert">{detailsError}</p>}
            {!loading && !loadError && totalOrganizations === null && (
                <p role="status">Общее количество недоступно. Результаты поиска сохранены.{' '}
                    <button type="button" className="secondary-button" onClick={onReload}>
                        Обновить итоги
                    </button>
                </p>
            )}

            {onFiltersChange && <form className="organization-directory-filter" role="search"
                onSubmit={event => {event.preventDefault(); applyFilters(searchDraft, type, status)}}>
                <label htmlFor="organizations-query">Название</label>
                <input id="organizations-query" type="search" maxLength={255} value={searchDraft}
                    onChange={event => setSearchDraft(event.target.value)} placeholder="Поиск по названию" />
                <label htmlFor="organizations-type">Тип</label>
                <select id="organizations-type" value={type}
                    onChange={event => applyFilters(searchDraft, event.target.value, status)}>
                    <option value="">Все типы</option><option value="PLATFORM">Системная</option>
                    <option value="TENANT">Клиентская</option>
                </select>
                <label htmlFor="organizations-status">Статус</label>
                <select id="organizations-status" value={status}
                    onChange={event => applyFilters(searchDraft, type, event.target.value)}>
                    <option value="">Все статусы</option><option value="ACTIVE">Включённые</option>
                    <option value="DISABLED">Отключённые</option>
                </select>
                <button type="submit">Найти</button>
                <button type="button" className="secondary-button" onClick={() => {
                    setSearchDraft(''); applyFilters('', '', '')
                }}>Сбросить</button>
                <span>Найдено: {loading || loadError ? '—' : totalMatched ?? '—'}</span>
            </form>}

            <span className="resizable-scroll-region__upper-boundary-marker"
                  data-resizable-upper-boundary aria-hidden="true" />
        </div>
    )

    const footer = hasRows ? (
        <div className="organizations-list-footer">
            <span className="organizations-list-footer__count">
                {`Показано ${organizations.length}${(totalMatched ?? visibleTotal) === null ? '' : ` из ${totalMatched ?? visibleTotal}`}`}
            </span>
            {totalPages > 1 && (
                <nav className="pagination organizations-pagination" aria-label="Страницы списка организаций">
                    <button type="button" className="secondary-button"
                            disabled={page === 0 || loading || hasPendingAction}
                            onClick={() => onPageChange(Math.max(0, page - 1))}>Предыдущая</button>
                    <span>Страница {page + 1} из {pageCount}</span>
                    <button type="button" className="secondary-button"
                            disabled={page + 1 >= totalPages || loading || hasPendingAction}
                            onClick={() => onPageChange(page + 1)}>Следующая</button>
                </nav>
            )}
        </div>
    ) : null

    return <>
        <ResizableScrollRegion storageKey="safeai:organizations-table-height:v4"
            label="список организаций" preserveUpperContent upper={upper} footer={footer}
            lowerClassName="organizations-table-card" viewportClassName="organizations-table-scroll"
            footerClassName="organizations-table-footer"
            defaultHeight={1600} autoFitHeight={1600} minHeight={160} maxHeight={1600} minUpperHeight={112}>
            {loading && <LoadingState message="Загрузка организаций…" variant="inline" />}
            {!loading && loadError && (
                <ErrorState title="Ошибка загрузки организаций" message={loadError}
                    variant="inline" action={<button type="button" onClick={onReload}>Повторить</button>} />
            )}
            {!loading && !loadError && organizations.length === 0 && (
                <EmptyState message="Организации не найдены." variant="inline" />
            )}
            {hasRows && (
                <table className="admin-table organizations-table">
                    <thead><tr>
                        <th>Организация</th>
                        <th>Тип <ColumnHelpButton column="type" label="Тип" onOpen={setHelpColumn} /></th>
                        <th>Статус <ColumnHelpButton column="status" label="Статус" onOpen={setHelpColumn} /></th>
                        <th>Создана</th>
                        <th>Действия</th>
                    </tr></thead>
                    <tbody>
                    {organizations.map(organization => {
                        const mutable = canMutateOrganization(organization)
                        const updating = hasPendingAction || impactLoadingId === organization.id
                        return <tr key={organization.id} className={!organization.enabled
                            ? 'organizations-table__row--disabled'
                            : organization.type === 'PLATFORM' ? 'organizations-table__row--platform' : undefined}>
                            <td><button type="button" className="organization-name-cell organization-name-link"
                                disabled={detailsLoadingId === organization.id}
                                aria-label={`Открыть карточку организации ${organization.name}`}
                                onClick={() => void onDetails(organization)}>{organization.name}</button></td>
                            <td><OrganizationTypeBadge type={organization.type} /></td>
                            <td>
                                <span title={mutable ? undefined : getProtectionLabel(organization)}>
                                    <OrganizationStatusBadge enabled={organization.enabled} />
                                </span>
                            </td>
                            <td>{formatDateTime(organization.createdAt)}</td>
                            <td className="actions-cell">
                                <div className="organization-actions org-actions-compact">
                                    <button type="button" className="secondary-button"
                                            disabled={detailsLoadingId === organization.id}
                                            onClick={() => void onDetails(organization)}>
                                        {detailsLoadingId === organization.id ? 'Загрузка…' : 'Подробнее'}
                                    </button>
                                    {mutable && <OrganizationActionsMenu
                                        organizationName={organization.name}
                                        enabled={organization.enabled}
                                        disabled={updating}
                                        onRename={() => onRename(organization)}
                                        onToggleStatus={() => organization.enabled
                                            ? void onDisable(organization) : onEnable(organization)} /> }
                                </div>
                                {!mutable && <span className="organization-protection-label">
                                    {getProtectionLabel(organization)}
                                </span>}
                            </td>
                        </tr>
                    })}
                    </tbody>
                </table>
            )}
        </ResizableScrollRegion>
        {createOpen && <Modal title="Создать организацию" size="sm" onClose={closeCreate} className="admin-details-dialog"
            closeDisabled={creating} closeOnBackdrop={false}>
            <form className="form org-create-modal-form" onSubmit={async event => {
                event.preventDefault()
                if (creating || hasPendingAction || !name.trim()) return
                if (await onCreate()) setCreateOpen(false)
            }}>
                <label>Название организации
                    <input value={name} onChange={event => onNameChange(event.target.value)}
                        placeholder="Например, ООО «Ромашка»" maxLength={255} required autoFocus
                        disabled={creating || hasPendingAction} />
                </label>
                <p className="org-create-modal-note">После создания организация будет включена. Вы сможете добавить пользователей и настроить доступ к AI-моделям.</p>
                {createError && <div className="error" role="alert">{createError}</div>}
                <div className="modal-actions">
                    <button type="button" className="secondary-button" disabled={creating} onClick={closeCreate}>Отмена</button>
                    <button type="submit" disabled={creating || hasPendingAction || !name.trim()}>
                        {creating ? 'Создание…' : 'Создать организацию'}
                    </button>
                </div>
            </form>
        </Modal>}
        {helpColumn !== null && <Modal title={COLUMN_HELP[helpColumn].title}
            onClose={() => setHelpColumn(null)} size="sm">
            <div className="org-column-dialog">
                <p>{COLUMN_HELP[helpColumn].body}</p>
                <div className="modal-actions">
                    <button type="button" className="secondary-button"
                        onClick={() => setHelpColumn(null)}>Понятно</button>
                </div>
            </div>
        </Modal>}
    </>
}

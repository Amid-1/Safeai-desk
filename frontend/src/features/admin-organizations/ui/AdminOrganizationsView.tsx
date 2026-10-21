// frontend/src/features/admin-organizations/ui/AdminOrganizationsView.tsx
import { useState } from 'react'
import type { Organization } from '../../../entities/organization/api/organizationApi'
import Modal from '../../../shared/ui/Modal'
import { formatDateTime } from '../../../shared/lib/format'
import ResizableScrollRegion from '../../../shared/ui/ResizableScrollRegion'
import { EmptyState, ErrorState, LoadingState } from '../../../shared/ui/StateBlock'
import { canMutateOrganization, getProtectionLabel } from '../lib/adminOrganizationsSupport'
import {
    OrganizationId, OrganizationStatusBadge, OrganizationTypeInfo, OrganizationVersionInfo,
} from './AdminOrganizationsUi'

const COLUMN_HELP = {
    id: {
        title: 'ID организации',
        body: 'Уникальный идентификатор организации. Его можно скопировать для обращения в поддержку, поиска событий аудита или настройки интеграции. Для обычной работы с пользователями он не требуется.',
    },
    type: {
        title: 'Тип организации',
        body: 'Клиентская — отдельная организация со своими пользователями и настройками. Системная — защищённая организация, необходимая для работы SafeAI Desk. Тип устанавливает сервер, менять его нельзя.',
    },
    status: {
        title: 'Статус организации',
        body: 'Включена — организация не блокирует вход своих включённых пользователей в SafeAI Desk. Отключена — вход и использование сервиса этой организацией заблокированы. Для клиентской организации нажмите «Отключить» или «Включить» под статусом: откроется подтверждение. Статус не означает, что пользователь сейчас в сети.',
    },
    version: {
        title: 'Версия записи',
        body: 'Номер изменений организации. Сервер использует его для защиты от конфликтующих одновременных правок. Новая организация обычно имеет версию v0. Это не версия программы, вручную менять её нельзя.',
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
    mutationError: string
    createError: string
    success: string
    loading: boolean
    creating: boolean
    hasPendingAction: boolean
    page: number
    totalPages: number
    name: string
    detailsLoadingId: string | null
    impactLoadingId: string | null
    onNameChange: (value: string) => void
    onCreate: () => Promise<void>
    onReload: () => void
    onDetails: (organization: Organization) => Promise<void>
    onRename: (organization: Organization) => void
    onDisable: (organization: Organization) => Promise<void>
    onEnable: (organization: Organization) => void
    onPageChange: (page: number) => void
}

/** Keep the protected PLATFORM row read-only; all mutations use existing confirmations. */
export function AdminOrganizationsView({
    organizations, loadError, mutationError, createError, success,
    loading, creating, hasPendingAction, page, totalPages, name,
    detailsLoadingId, impactLoadingId, onNameChange, onCreate,
    onReload, onDetails, onRename, onDisable, onEnable, onPageChange,
}: AdminOrganizationsViewProps) {
    const [helpColumn, setHelpColumn] = useState<ColumnKey | null>(null)
    const hasRows = !loading && !loadError && organizations.length > 0
    const pageCount = Math.max(1, totalPages)

    const upper = (
        <div className="organizations-page__upper">
            <header className="organizations-page__header">
                <div>
                    <span className="organizations-page__eyebrow">УПРАВЛЕНИЕ ОРГАНИЗАЦИЯМИ</span>
                    <h1>Организации</h1>
                    <p>Создавайте организации клиентов, управляйте их доступом и просматривайте сведения.</p>
                </div>
                <div className="organizations-page__summary" aria-label="Количество организаций в списке">
                    <span>Показано в списке</span>
                    <strong>{organizations.length}</strong>
                    <small>{totalPages > 1 ? `Страница ${page + 1} из ${pageCount}` : 'Одна страница'}</small>
                </div>
            </header>

            {mutationError && <div className="error" role="alert" aria-live="assertive">{mutationError}</div>}
            {success && <div className="success" role="status" aria-live="polite">{success}</div>}

            <section className="card organizations-create-card" aria-labelledby="create-client-organization-title">
                <div className="organizations-section-heading">
                    <div>
                        <span>ДОБАВЛЕНИЕ КЛИЕНТА</span>
                        <h2 id="create-client-organization-title">Создать организацию</h2>
                    </div>
                    <div className="org-create-info">
                        Новая организация получит собственную область пользователей и настроек.
                        <div className="org-create-defaults" role="note">
                            <span><strong>Тип:</strong> клиентская</span>
                            <span><strong>Статус после создания:</strong> включена</span>
                        </div>
                        <p className="org-create-privilege">Клиентская организация имеет собственных пользователей и настройки. После создания её доступ включён; при необходимости его можно отключить через подтверждение в списке. Создание разрешено только SUPER_ADMIN. Тип и начальный статус определяет сервер; тип нельзя изменить, а постоянное удаление организаций здесь не предусмотрено.</p>
                        <details>
                            <summary>Чем отличается системная организация?</summary>
                            <p>SafeAI Platform обслуживает саму платформу и защищена сервером.
                                Здесь создаются только организации клиентов.</p>
                        </details>
                    </div>
                </div>
                <form className="organizations-create-form" onSubmit={event => {
                    event.preventDefault()
                    void onCreate()
                }}>
                    <label>Название организации
                        <input value={name} onChange={event => onNameChange(event.target.value)}
                               placeholder="Например, ООО «Ромашка»" maxLength={255} required
                               disabled={creating || hasPendingAction} />
                    </label>
                    <button type="submit" disabled={creating || hasPendingAction || !name.trim()}>
                        {creating ? 'Создание…' : 'Создать организацию'}
                    </button>
                </form>
                {createError && <div className="error organizations-create-error" role="alert">{createError}</div>}
            </section>
            <span className="resizable-scroll-region__upper-boundary-marker"
                  data-resizable-upper-boundary aria-hidden="true" />
        </div>
    )

    const footer = hasRows ? (
        <div className="organizations-list-footer">
            <span className="organizations-list-footer__count">
                {totalPages > 1
                    ? `Показано ${organizations.length} на странице ${page + 1} из ${pageCount}`
                    : `Всего организаций: ${organizations.length}`}
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
        <ResizableScrollRegion storageKey="safeai:organizations-table-height:v3"
            label="список организаций" preserveUpperContent upper={upper} footer={footer}
            lowerClassName="organizations-table-card" viewportClassName="organizations-table-scroll"
            footerClassName="organizations-table-footer"
            defaultHeight={360} minHeight={160} maxHeight={760} minUpperHeight={260}>
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
                        <th>ID <ColumnHelpButton column="id" label="ID" onOpen={setHelpColumn} /></th>
                        <th>Тип <ColumnHelpButton column="type" label="Тип" onOpen={setHelpColumn} /></th>
                        <th>Статус <ColumnHelpButton column="status" label="Статус" onOpen={setHelpColumn} /></th>
                        <th>Версия <ColumnHelpButton column="version" label="Версия" onOpen={setHelpColumn} /></th>
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
                            <td><strong className="organization-name-cell">{organization.name}</strong></td>
                            <td><OrganizationId id={organization.id} /></td>
                            <td><OrganizationTypeInfo type={organization.type} /></td>
                            <td>
                                {mutable ? (
                                    <button type="button" className="org-status-action"
                                            title={`Открыть подтверждение: ${organization.enabled ? 'отключить' : 'включить'} организацию`}
                                            aria-label={`${organization.enabled ? 'Отключить' : 'Включить'} организацию ${organization.name}: изменить статус`}
                                            disabled={updating}
                                            onClick={() => organization.enabled
                                                ? void onDisable(organization) : onEnable(organization)}>
                                        <OrganizationStatusBadge enabled={organization.enabled} />
                                        <span className="org-status-action__hint">{organization.enabled ? 'Отключить' : 'Включить'}</span>
                                    </button>
                                ) : (
                                    <span title={getProtectionLabel(organization)}>
                                        <OrganizationStatusBadge enabled={organization.enabled} />
                                    </span>
                                )}
                            </td>
                            <td><OrganizationVersionInfo version={organization.version} /></td>
                            <td>{formatDateTime(organization.createdAt)}</td>
                            <td className="actions-cell">
                                <div className="organization-actions org-actions-compact">
                                    <button type="button" className="secondary-button"
                                            disabled={detailsLoadingId === organization.id}
                                            onClick={() => void onDetails(organization)}>
                                        {detailsLoadingId === organization.id ? 'Загрузка…' : 'Подробнее'}
                                    </button>
                                    {mutable && <button type="button" className="secondary-button"
                                        disabled={hasPendingAction} onClick={() => onRename(organization)}>
                                        Переименовать
                                    </button>}
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

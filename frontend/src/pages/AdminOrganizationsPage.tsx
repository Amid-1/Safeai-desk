// frontend/src/pages/AdminOrganizationsPage.tsx
import { useEffect, useState } from 'react'
import {
    createOrganization,
    getOrganizations,
    updateOrganizationEnabled,
    updateOrganizationName,
} from '../api/organizationApi'
import type { Organization } from '../api/organizationApi'
import { getApiErrorMessage } from '../api/http'
import { formatDateTime } from '../utils/format'
import { getPageContent, getPageTotalPages } from '../utils/page'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlock'

const PAGE_SIZE = 50
const SUCCESS_MESSAGE_TIMEOUT_MS = 4000
const PLATFORM_ORGANIZATION_ID = '00000000-0000-0000-0000-000000000001'

type ConfirmState = {
    organization: Organization
} | null

function AdminOrganizationsPage() {
    const [organizations, setOrganizations] = useState<Organization[]>([])
    const [error, setError] = useState('')
    const [success, setSuccess] = useState('')
    const [loading, setLoading] = useState(true)
    const [creating, setCreating] = useState(false)
    const [actionOrganizationId, setActionOrganizationId] = useState<string | null>(null)

    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(1)

    const [name, setName] = useState('')
    const [renameOrganization, setRenameOrganization] = useState<Organization | null>(null)
    const [renameValue, setRenameValue] = useState('')
    const [confirmState, setConfirmState] = useState<ConfirmState>(null)

    useEffect(() => {
        void loadOrganizations(page)
    }, [page])

    useEffect(() => {
        if (!success) {
            return
        }

        const timeoutId = window.setTimeout(() => {
            setSuccess('')
        }, SUCCESS_MESSAGE_TIMEOUT_MS)

        return () => {
            window.clearTimeout(timeoutId)
        }
    }, [success])

    async function loadOrganizations(nextPage = page) {
        setError('')
        setLoading(true)

        try {
            const data = await getOrganizations(nextPage, PAGE_SIZE)

            setOrganizations(getPageContent(data))
            setTotalPages(getPageTotalPages(data))
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось загрузить организации.'))
        } finally {
            setLoading(false)
        }
    }

    function openRenameModal(organization: Organization) {
        if (isPlatformOrganization(organization)) {
            return
        }

        setRenameOrganization(organization)
        setRenameValue(organization.name)
        setError('')
        setSuccess('')
    }

    function closeRenameModal() {
        setRenameOrganization(null)
        setRenameValue('')
    }

    async function submitCreateOrganization() {
        const normalizedName = name.trim()

        if (!normalizedName) {
            setError('Введите название организации.')
            return
        }

        setError('')
        setSuccess('')
        setCreating(true)

        try {
            const created = await createOrganization({
                name: normalizedName,
            })

            setSuccess(`Организация ${created.name} создана.`)
            setName('')
            setPage(0)

            await loadOrganizations(0)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось создать организацию.'))
        } finally {
            setCreating(false)
        }
    }

    async function submitRenameOrganization() {
        if (!renameOrganization) {
            return
        }

        if (isPlatformOrganization(renameOrganization)) {
            setError('Платформенную организацию нельзя переименовывать.')
            closeRenameModal()
            return
        }

        const normalizedName = renameValue.trim()

        if (!normalizedName) {
            setError('Введите новое название организации.')
            return
        }

        setActionOrganizationId(renameOrganization.id)

        try {
            const updated = await updateOrganizationName(renameOrganization.id, {
                name: normalizedName,
            })

            replaceOrganization(updated)
            setSuccess(`Организация переименована в ${updated.name}.`)
            closeRenameModal()
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось переименовать организацию.'))
        } finally {
            setActionOrganizationId(null)
        }
    }

    async function handleConfirmToggleEnabled() {
        if (!confirmState) {
            return
        }

        const organization = confirmState.organization

        if (isPlatformOrganization(organization)) {
            setError('Платформенную организацию нельзя отключать.')
            setConfirmState(null)
            return
        }

        const nextEnabled = !organization.enabled

        setActionOrganizationId(organization.id)
        setError('')
        setSuccess('')

        try {
            const updated = await updateOrganizationEnabled(organization.id, {
                enabled: nextEnabled,
            })

            replaceOrganization(updated)

            setSuccess(
                updated.enabled
                    ? `Организация ${updated.name} включена.`
                    : `Организация ${updated.name} отключена.`
            )

            setConfirmState(null)
        } catch (err) {
            setError(getApiErrorMessage(err, 'Не удалось изменить статус организации.'))
        } finally {
            setActionOrganizationId(null)
        }
    }

    function replaceOrganization(updated: Organization) {
        setOrganizations((prev) =>
            prev.map((organization) =>
                organization.id === updated.id ? updated : organization
            )
        )
    }

    return (
        <div className="page">
            <h1>Организации</h1>

            {error && <div className="error">{error}</div>}
            {success && <div className="success">{success}</div>}

            <div className="card form-card">
                <h2>Создать организацию</h2>

                <form
                    className="form"
                    onSubmit={(event) => {
                        event.preventDefault()
                        void submitCreateOrganization()
                    }}
                >
                    <label>
                        Название организации
                        <input
                            value={name}
                            onChange={(event) => setName(event.target.value)}
                            placeholder="Demo Company"
                            maxLength={255}
                        />
                    </label>

                    <button disabled={creating || !name.trim()}>
                        {creating ? 'Создание...' : 'Создать организацию'}
                    </button>
                </form>
            </div>

            {loading && <LoadingState message="Загрузка организаций..." />}

            {!loading && error && organizations.length === 0 && (
                <ErrorState
                    title="Ошибка загрузки организаций"
                    message={error}
                    action={
                        <button type="button" onClick={() => void loadOrganizations(page)}>
                            Retry
                        </button>
                    }
                />
            )}

            {!loading && organizations.length === 0 && (
                <EmptyState message="Организации не найдены." />
            )}

            {!loading && organizations.length > 0 && (
                <div className="card table-card">
                    <table>
                        <thead>
                        <tr>
                            <th>НАЗВАНИЕ</th>
                            <th>ID</th>
                            <th>СТАТУС</th>
                            <th>СОЗДАНА</th>
                            <th>ДЕЙСТВИЯ</th>
                        </tr>
                        </thead>

                        <tbody>
                        {organizations.map((organization) => {
                            const isBusy = actionOrganizationId === organization.id
                            const platformOrganization = isPlatformOrganization(organization)

                            return (
                                <tr key={organization.id}>
                                    <td>{organization.name}</td>
                                    <td>{organization.id}</td>
                                    <td>
                                        <span
                                            className={
                                                organization.enabled
                                                    ? 'status-badge status-enabled'
                                                    : 'status-badge status-disabled'
                                            }
                                        >
                                            {organization.enabled ? 'включена' : 'отключена'}
                                        </span>
                                    </td>
                                    <td>{formatDateTime(organization.createdAt)}</td>
                                    <td className="actions-cell">
                                        {platformOrganization ? (
                                            <span className="muted">
                                                Платформенная организация
                                            </span>
                                        ) : (
                                            <div className="table-actions">
                                                <button
                                                    type="button"
                                                    className="secondary-button"
                                                    disabled={isBusy}
                                                    onClick={() => openRenameModal(organization)}
                                                >
                                                    Переименовать
                                                </button>

                                                <button
                                                    type="button"
                                                    className={
                                                        organization.enabled
                                                            ? 'danger-button'
                                                            : 'secondary-button'
                                                    }
                                                    disabled={isBusy}
                                                    onClick={() => setConfirmState({ organization })}
                                                >
                                                    {organization.enabled ? 'Отключить' : 'Включить'}
                                                </button>
                                            </div>
                                        )}
                                    </td>
                                </tr>
                            )
                        })}
                        </tbody>
                    </table>

                    <div className="pagination">
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={page === 0 || loading}
                            onClick={() => setPage((prev) => Math.max(0, prev - 1))}
                        >
                            Назад
                        </button>

                        <span>
                            Страница {page + 1} из {Math.max(totalPages, 1)}
                        </span>

                        <button
                            type="button"
                            className="secondary-button"
                            disabled={page + 1 >= totalPages || loading}
                            onClick={() => setPage((prev) => prev + 1)}
                        >
                            Вперед
                        </button>
                    </div>
                </div>
            )}

            {renameOrganization && (
                <Modal
                    title={`Переименовать организацию: ${renameOrganization.name}`}
                    onClose={closeRenameModal}
                >
                    <form
                        className="form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void submitRenameOrganization()
                        }}
                    >
                        <label>
                            Новое название
                            <input
                                value={renameValue}
                                onChange={(event) => setRenameValue(event.target.value)}
                                maxLength={255}
                                autoFocus
                            />
                        </label>

                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={actionOrganizationId === renameOrganization.id}
                                onClick={closeRenameModal}
                            >
                                Отмена
                            </button>

                            <button
                                disabled={
                                    actionOrganizationId === renameOrganization.id
                                    || !renameValue.trim()
                                }
                            >
                                Сохранить
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {confirmState && (
                <ConfirmDialog
                    title={
                        confirmState.organization.enabled
                            ? 'Отключить организацию'
                            : 'Включить организацию'
                    }
                    message={
                        confirmState.organization.enabled
                            ? `Отключить организацию ${confirmState.organization.name}? Пользователи этой организации потеряют доступ.`
                            : `Включить организацию ${confirmState.organization.name}?`
                    }
                    confirmText={
                        confirmState.organization.enabled ? 'Отключить' : 'Включить'
                    }
                    danger={confirmState.organization.enabled}
                    loading={actionOrganizationId === confirmState.organization.id}
                    onCancel={() => setConfirmState(null)}
                    onConfirm={() => void handleConfirmToggleEnabled()}
                />
            )}
        </div>
    )
}

function isPlatformOrganization(organization: Organization): boolean {
    return organization.id === PLATFORM_ORGANIZATION_ID
}

export default AdminOrganizationsPage
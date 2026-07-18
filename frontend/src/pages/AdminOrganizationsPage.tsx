// frontend/src/pages/AdminOrganizationsPage.tsx
import { useEffect, useRef, useState } from 'react'
import {
    createOrganization,
    getOrganizations,
    updateOrganizationEnabled,
    updateOrganizationName,
} from '../api/organizationApi'
import type { Organization } from '../api/organizationApi'
import { getApiErrorMessage } from '../api/http'
import { formatDateTime } from '../utils/format'
import { normalizePageResponse } from '../utils/page'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlock'

const PAGE_SIZE = 50
const SUCCESS_MESSAGE_TIMEOUT_MS = 4000
const PLATFORM_ORGANIZATION_ID = '00000000-0000-0000-0000-000000000001'

type ConfirmState = { organization: Organization } | null

function AdminOrganizationsPage() {
    const [organizations, setOrganizations] = useState<Organization[]>([])
    const [loadError, setLoadError] = useState('')
    const [mutationError, setMutationError] = useState('')
    const [renameError, setRenameError] = useState('')
    const [success, setSuccess] = useState('')
    const [loading, setLoading] = useState(true)
    const [creating, setCreating] = useState(false)
    const [actionOrganizationId, setActionOrganizationId] = useState<string | null>(null)
    const [page, setPage] = useState(0)
    const [totalPages, setTotalPages] = useState(0)
    const [reloadToken, setReloadToken] = useState(0)
    const [name, setName] = useState('')
    const [renameOrganization, setRenameOrganization] = useState<Organization | null>(null)
    const [renameValue, setRenameValue] = useState('')
    const [confirmState, setConfirmState] = useState<ConfirmState>(null)

    const loadSequenceRef = useRef(0)

    useEffect(() => {
        const sequence = ++loadSequenceRef.current

        async function loadOrganizations() {
            setLoading(true)
            setLoadError('')

            try {
                const response = await getOrganizations(page, PAGE_SIZE)

                if (sequence !== loadSequenceRef.current) {
                    return
                }

                const normalized = normalizePageResponse(response)
                setOrganizations(normalized.content)
                setTotalPages(normalized.totalPages)
            } catch (error) {
                if (sequence === loadSequenceRef.current) {
                    setOrganizations([])
                    setTotalPages(0)
                    setLoadError(
                        getApiErrorMessage(error, 'Не удалось загрузить организации.')
                    )
                }
            } finally {
                if (sequence === loadSequenceRef.current) {
                    setLoading(false)
                }
            }
        }

        void loadOrganizations()

        return () => {
            loadSequenceRef.current += 1
        }
    }, [page, reloadToken])

    useEffect(() => {
        if (!success) {
            return
        }

        const timeoutId = window.setTimeout(() => setSuccess(''), SUCCESS_MESSAGE_TIMEOUT_MS)
        return () => window.clearTimeout(timeoutId)
    }, [success])

    function requestReloadFromFirstPage() {
        if (page === 0) {
            setReloadToken((value) => value + 1)
        } else {
            setPage(0)
        }
    }

    function openRenameModal(organization: Organization) {
        if (isPlatformOrganization(organization)) {
            return
        }

        setRenameOrganization(organization)
        setRenameValue(organization.name)
        setRenameError('')
        setMutationError('')
        setSuccess('')
    }

    function closeRenameModal() {
        if (renameOrganization && actionOrganizationId === renameOrganization.id) {
            return
        }

        setRenameOrganization(null)
        setRenameValue('')
        setRenameError('')
    }

    async function submitCreateOrganization() {
        const normalizedName = name.trim()

        if (!normalizedName) {
            setMutationError('Введите название организации.')
            return
        }

        setMutationError('')
        setSuccess('')
        setCreating(true)

        try {
            const created = await createOrganization({ name: normalizedName })
            setName('')
            setSuccess(`Организация «${created.name}» создана.`)
            requestReloadFromFirstPage()
        } catch (error) {
            setMutationError(
                getApiErrorMessage(error, 'Не удалось создать организацию.')
            )
        } finally {
            setCreating(false)
        }
    }

    async function submitRenameOrganization() {
        if (!renameOrganization) {
            return
        }

        const normalizedName = renameValue.trim()

        if (!normalizedName) {
            setRenameError('Введите новое название организации.')
            return
        }

        if (normalizedName === renameOrganization.name) {
            closeRenameModal()
            return
        }

        setRenameError('')
        setActionOrganizationId(renameOrganization.id)

        try {
            const updated = await updateOrganizationName(renameOrganization.id, {
                name: normalizedName,
            })

            replaceOrganization(updated)
            setSuccess(`Организация переименована в «${updated.name}».`)
            setRenameOrganization(null)
            setRenameValue('')
        } catch (error) {
            setRenameError(
                getApiErrorMessage(error, 'Не удалось переименовать организацию.')
            )
        } finally {
            setActionOrganizationId(null)
        }
    }

    async function handleConfirmToggleEnabled() {
        if (!confirmState) {
            return
        }

        const organization = confirmState.organization
        const nextEnabled = !organization.enabled

        setMutationError('')
        setSuccess('')
        setActionOrganizationId(organization.id)

        try {
            const updated = await updateOrganizationEnabled(organization.id, {
                enabled: nextEnabled,
            })

            replaceOrganization(updated)
            setSuccess(
                updated.enabled
                    ? `Организация «${updated.name}» включена.`
                    : `Организация «${updated.name}» отключена.`
            )
            setConfirmState(null)
        } catch (error) {
            setMutationError(
                getApiErrorMessage(error, 'Не удалось изменить статус организации.')
            )
        } finally {
            setActionOrganizationId(null)
        }
    }

    function replaceOrganization(updated: Organization) {
        setOrganizations((current) =>
            current.map((organization) =>
                organization.id === updated.id ? updated : organization
            )
        )
    }

    return (
        <div className="page">
            <h1>Организации</h1>

            {mutationError && <div className="error">{mutationError}</div>}
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
                            required
                            disabled={creating}
                        />
                    </label>
                    <button disabled={creating || !name.trim()}>
                        {creating ? 'Создание...' : 'Создать организацию'}
                    </button>
                </form>
            </div>

            {loading && <LoadingState message="Загрузка организаций..." />}

            {!loading && loadError && (
                <ErrorState
                    title="Ошибка загрузки организаций"
                    message={loadError}
                    action={
                        <button type="button" onClick={() => setReloadToken((value) => value + 1)}>
                            Повторить
                        </button>
                    }
                />
            )}

            {!loading && !loadError && organizations.length === 0 && (
                <EmptyState message="Организации не найдены." />
            )}

            {!loading && !loadError && organizations.length > 0 && (
                <div className="card table-card">
                    <table className="admin-table">
                        <thead>
                        <tr>
                            <th>Название</th>
                            <th>ID</th>
                            <th>Статус</th>
                            <th>Создана</th>
                            <th>Действия</th>
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
                                            <span className="muted">Платформенная организация</span>
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
                            onClick={() => setPage((value) => Math.max(0, value - 1))}
                        >
                            Назад
                        </button>
                        <span>Страница {page + 1} из {Math.max(totalPages, 1)}</span>
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={page + 1 >= totalPages || loading}
                            onClick={() => setPage((value) => value + 1)}
                        >
                            Вперёд
                        </button>
                    </div>
                </div>
            )}

            {renameOrganization && (
                <Modal
                    title={`Переименовать организацию: ${renameOrganization.name}`}
                    onClose={closeRenameModal}
                    closeDisabled={actionOrganizationId === renameOrganization.id}
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
                                required
                                autoFocus
                                disabled={actionOrganizationId === renameOrganization.id}
                            />
                        </label>
                        {renameError && <div className="error">{renameError}</div>}
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
                                {actionOrganizationId === renameOrganization.id
                                    ? 'Сохранение...'
                                    : 'Сохранить'}
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
                            ? `Отключить организацию «${confirmState.organization.name}»? Пользователи потеряют доступ, активные сессии будут отозваны.`
                            : `Включить организацию «${confirmState.organization.name}»?`
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

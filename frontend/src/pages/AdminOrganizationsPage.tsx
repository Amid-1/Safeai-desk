// frontend/src/pages/AdminOrganizationsPage.tsx
import {
    useEffect,
    useRef,
    useState,
} from 'react'
import type { ReactNode, SyntheticEvent } from 'react'
import {
    createOrganization,
    disableOrganization,
    enableOrganization,
    getOrganizationDetails,
    getOrganizationDisableImpact,
    getOrganizations,
    isProtectedOrganization,
    normalizeOrganizationConfirmation,
    normalizeOrganizationName,
    updateOrganizationName,
} from '../api/organizationApi'
import type {
    Organization,
    OrganizationDisableImpact,
} from '../api/organizationApi'
import {
    ApiError,
    getApiErrorMessage,
} from '../api/http'
import { formatDateTime } from '../utils/format'
import {
    normalizePageResponse,
} from '../utils/page'
import {
    useAutoClearMessage,
} from '../hooks/useAutoClearMessage'
import Modal from '../components/Modal'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../components/StateBlock'
import PageErrorBoundary
    from '../components/PageErrorBoundary'
import './AdminOrganizationsPage.css'

const PAGE_SIZE = 50
const SUCCESS_MESSAGE_TIMEOUT_MS = 4_000

type PendingAction =
    | {
        organizationId: string
        type:
            | 'RENAME'
            | 'DISABLE'
            | 'ENABLE'
    }
    | null

type DisableDialogState = {
    organization: Organization
    impact: OrganizationDisableImpact
    confirmationName: string
} | null

function AdminOrganizationsPage() {
    return (
        <PageErrorBoundary>
            <AdminOrganizationsPageContent />
        </PageErrorBoundary>
    )
}

function AdminOrganizationsPageContent() {
    const [
        organizations,
        setOrganizations,
    ] = useState<Organization[]>([])

    const [loadError, setLoadError] =
        useState('')
    const [
        mutationError,
        setMutationError,
    ] = useState('')
    const [renameError, setRenameError] =
        useState('')
    const [success, setSuccess] =
        useState('')

    const [loading, setLoading] =
        useState(true)
    const [creating, setCreating] =
        useState(false)
    const [
        pendingAction,
        setPendingAction,
    ] = useState<PendingAction>(null)
    const [
        impactLoadingId,
        setImpactLoadingId,
    ] = useState<string | null>(null)

    const [page, setPage] =
        useState(0)
    const [totalPages, setTotalPages] =
        useState(0)
    const [reloadToken, setReloadToken] =
        useState(0)

    const [name, setName] =
        useState('')
    const [
        renameOrganization,
        setRenameOrganization,
    ] = useState<Organization | null>(
        null,
    )
    const [
        renameValue,
        setRenameValue,
    ] = useState('')
    const [
        enableOrganizationTarget,
        setEnableOrganizationTarget,
    ] = useState<Organization | null>(
        null,
    )
    const [
        disableDialog,
        setDisableDialog,
    ] = useState<DisableDialogState>(null)

    const [
        detailsOrganization,
        setDetailsOrganization,
    ] = useState<Organization | null>(null)
    const [
        detailsLoadingId,
        setDetailsLoadingId,
    ] = useState<string | null>(null)
    const [detailsError, setDetailsError] =
        useState('')

    const loadSequenceRef = useRef(0)
    const loadControllerRef =
        useRef<AbortController | null>(null)
    const impactSequenceRef = useRef(0)
    const impactControllerRef =
        useRef<AbortController | null>(null)
    const detailsSequenceRef = useRef(0)
    const detailsControllerRef =
        useRef<AbortController | null>(null)

    const creatingRef = useRef(false)
    const pendingActionRef =
        useRef<PendingAction>(null)

    const hasPendingAction =
        pendingAction !== null

    useEffect(() => {
        pendingActionRef.current =
            pendingAction
    }, [pendingAction])

    useEffect(() => {
        const sequence =
            ++loadSequenceRef.current

        loadControllerRef.current?.abort()

        const controller =
            new AbortController()

        loadControllerRef.current =
            controller

        async function loadOrganizations() {
            setLoading(true)
            setLoadError('')

            try {
                const response =
                    await getOrganizations(
                        page,
                        PAGE_SIZE,
                        {
                            signal:
                                controller.signal,
                        },
                    )

                if (
                    sequence
                    !== loadSequenceRef.current
                ) {
                    return
                }

                const normalized =
                    normalizePageResponse(response)

                if (
                    normalized.totalPages === 0
                    && page !== 0
                ) {
                    setPage(0)
                    return
                }

                if (
                    normalized.totalPages > 0
                    && page
                        >= normalized.totalPages
                ) {
                    setPage(
                        normalized.totalPages - 1,
                    )
                    return
                }

                setOrganizations(
                    normalized.content,
                )
                setTotalPages(
                    normalized.totalPages,
                )
            } catch (error) {
                if (
                    sequence
                    === loadSequenceRef.current
                    && !isRequestAborted(error)
                ) {
                    setOrganizations([])
                    setTotalPages(0)
                    setLoadError(
                        getApiErrorMessage(
                            error,
                            'Не удалось загрузить организации.',
                        ),
                    )
                }
            } finally {
                if (
                    sequence
                    === loadSequenceRef.current
                ) {
                    setLoading(false)
                }
            }
        }

        void loadOrganizations()

        return () => {
            controller.abort()
            loadSequenceRef.current += 1
        }
    }, [
        page,
        reloadToken,
    ])

    useEffect(() => {
        return () => {
            loadControllerRef.current?.abort()
            impactControllerRef.current?.abort()
            detailsControllerRef.current?.abort()
        }
    }, [])

    useAutoClearMessage(
        success,
        setSuccess,
        SUCCESS_MESSAGE_TIMEOUT_MS,
    )

    function requestReloadFromFirstPage() {
        if (page === 0) {
            setReloadToken(
                (value) => value + 1,
            )
        } else {
            setPage(0)
        }
    }

    async function submitCreateOrganization(
        event: SyntheticEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (creatingRef.current) {
            return
        }

        const normalizedName =
            normalizeOrganizationName(name)

        if (!normalizedName) {
            setMutationError(
                'Введите название организации.',
            )
            return
        }

        creatingRef.current = true
        setCreating(true)
        setMutationError('')
        setSuccess('')

        try {
            const created =
                await createOrganization({
                    name: normalizedName,
                })

            setName('')
            setSuccess(
                `Организация «${created.name}» создана.`,
            )
            requestReloadFromFirstPage()
        } catch (error) {
            setMutationError(
                getApiErrorMessage(
                    error,
                    'Не удалось создать организацию.',
                ),
            )
        } finally {
            creatingRef.current = false
            setCreating(false)
        }
    }

    async function openDetailsModal(
        organization: Organization,
    ) {
        const sequence =
            ++detailsSequenceRef.current

        detailsControllerRef.current?.abort()

        const controller =
            new AbortController()

        detailsControllerRef.current =
            controller

        setDetailsLoadingId(
            organization.id,
        )
        setDetailsError('')

        try {
            const details =
                await getOrganizationDetails(
                    organization.id,
                    {
                        signal:
                            controller.signal,
                    },
                )

            if (
                sequence
                !== detailsSequenceRef.current
            ) {
                return
            }

            setDetailsOrganization(details)
        } catch (error) {
            if (
                sequence
                === detailsSequenceRef.current
                && !isRequestAborted(error)
            ) {
                setDetailsError(
                    getApiErrorMessage(
                        error,
                        'Не удалось загрузить сведения об организации.',
                    ),
                )

                // Fallback на уже показанный page snapshot:
                // пользователь всё равно увидит сведения,
                // но предупреждение останется видимым.
                setDetailsOrganization(
                    organization,
                )
            }
        } finally {
            if (
                sequence
                === detailsSequenceRef.current
            ) {
                setDetailsLoadingId(null)
            }
        }
    }

    function closeDetailsModal() {
        detailsSequenceRef.current += 1
        detailsControllerRef.current?.abort()
        setDetailsOrganization(null)
        setDetailsLoadingId(null)
        setDetailsError('')
    }

    function openRenameModal(
        organization: Organization,
    ) {
        if (
            !canMutateOrganization(
                organization,
            )
        ) {
            setMutationError(
                getProtectionError(
                    organization,
                ),
            )
            return
        }

        setRenameOrganization(
            organization,
        )
        setRenameValue(
            organization.name,
        )
        setRenameError('')
        setMutationError('')
        setSuccess('')
    }

    function closeRenameModal() {
        if (hasPendingAction) {
            return
        }

        setRenameOrganization(null)
        setRenameValue('')
        setRenameError('')
    }

    async function submitRenameOrganization(
        event: SyntheticEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (!renameOrganization) {
            return
        }

        const normalizedName =
            normalizeOrganizationName(
                renameValue,
            )

        if (!normalizedName) {
            setRenameError(
                'Введите новое название организации.',
            )
            return
        }

        if (
            normalizedName
            === renameOrganization.name
        ) {
            closeRenameModal()
            return
        }

        await runOrganizationAction(
            {
                organizationId:
                    renameOrganization.id,
                type: 'RENAME',
            },
            async () => {
                const updated =
                    await updateOrganizationName(
                        renameOrganization.id,
                        {
                            name: normalizedName,
                            expectedVersion:
                                renameOrganization.version,
                        },
                    )

                setRenameOrganization(null)
                setRenameValue('')
                setSuccess(
                    `Организация переименована в «${updated.name}».`,
                )

                // После rename обязательно восстанавливаем
                // серверную сортировку.
                requestReloadFromFirstPage()
            },
            setRenameError,
            'Не удалось переименовать организацию.',
        )
    }

    async function openDisableDialog(
        organization: Organization,
    ) {
        if (
            !canMutateOrganization(
                organization,
            )
        ) {
            setMutationError(
                getProtectionError(
                    organization,
                ),
            )
            return
        }

        if (!organization.enabled) {
            return
        }

        const expectedVersion =
            organization.version

        const sequence =
            ++impactSequenceRef.current

        impactControllerRef.current?.abort()

        const controller =
            new AbortController()

        impactControllerRef.current =
            controller

        setImpactLoadingId(
            organization.id,
        )
        setMutationError('')

        try {
            const impact =
                await getOrganizationDisableImpact(
                    organization.id,
                    {
                        signal:
                            controller.signal,
                    },
                )

            if (
                sequence
                    !== impactSequenceRef.current
                || impact.organizationVersion
                    !== expectedVersion
            ) {
                if (
                    impact.organizationVersion
                    !== expectedVersion
                ) {
                    setMutationError(
                        'Организация изменилась до подтверждения. '
                        + 'Список будет обновлён.',
                    )
                    requestReloadFromFirstPage()
                }

                return
            }

            setDisableDialog({
                organization,
                impact,
                confirmationName: '',
            })
        } catch (error) {
            if (
                sequence
                === impactSequenceRef.current
                && !isRequestAborted(error)
            ) {
                setMutationError(
                    getApiErrorMessage(
                        error,
                        'Не удалось оценить последствия отключения.',
                    ),
                )
            }
        } finally {
            if (
                sequence
                === impactSequenceRef.current
            ) {
                setImpactLoadingId(null)
            }
        }
    }

    async function confirmDisableOrganization(
        event: SyntheticEvent<HTMLFormElement>,
    ) {
        event.preventDefault()

        if (!disableDialog) {
            return
        }

        const {
            organization,
            impact,
            confirmationName,
        } = disableDialog

        if (
            normalizeOrganizationConfirmation(
                confirmationName,
            )
            !== normalizeOrganizationConfirmation(
                organization.name,
            )
        ) {
            setMutationError(
                'Название организации не совпадает. '
                + 'Регистр, кавычки и лишние пробелы '
                + 'можно не повторять, но слова должны '
                + 'совпадать без опечаток.',
            )
            return
        }

        await runOrganizationAction(
            {
                organizationId:
                    organization.id,
                type: 'DISABLE',
            },
            async () => {
                const updated =
                    await disableOrganization(
                        organization.id,
                        {
                            expectedVersion:
                                impact.organizationVersion,
                            // Backend должен проверять именно
                            // то, что фактически ввёл оператор,
                            // а не известное frontend-у правильное имя.
                            confirmationName,
                        },
                    )

                setDisableDialog(null)
                setSuccess(
                    `Для организации «${updated.name}» запущено отключение `
                    + 'и отзыв активных сессий.',
                )
                requestReloadFromFirstPage()
            },
            setMutationError,
            'Не удалось отключить организацию.',
        )
    }

    async function confirmEnableOrganization() {
        if (!enableOrganizationTarget) {
            return
        }

        const organization =
            enableOrganizationTarget

        const expectedVersion =
            organization.version

        await runOrganizationAction(
            {
                organizationId:
                    organization.id,
                type: 'ENABLE',
            },
            async () => {
                const updated =
                    await enableOrganization(
                        organization.id,
                        {
                            expectedVersion,
                        },
                    )

                setEnableOrganizationTarget(
                    null,
                )
                setSuccess(
                    `Организация «${updated.name}» включена.`,
                )
                requestReloadFromFirstPage()
            },
            setMutationError,
            'Не удалось включить организацию.',
        )
    }

    async function runOrganizationAction(
        action: Exclude<
            PendingAction,
            null
        >,
        operation: () => Promise<void>,
        setError:
            (message: string) => void,
        fallback: string,
    ) {
        if (pendingActionRef.current) {
            return
        }

        pendingActionRef.current = action
        setPendingAction(action)
        setError('')
        setSuccess('')

        try {
            await operation()
        } catch (error) {
            if (isVersionConflict(error)) {
                setError(
                    'Организация была изменена другим администратором. '
                    + 'Загружены свежие данные; повторите решение.',
                )
                requestReloadFromFirstPage()
            } else {
                setError(
                    getApiErrorMessage(
                        error,
                        fallback,
                    ),
                )
            }
        } finally {
            pendingActionRef.current =
                null
            setPendingAction(null)
        }
    }

    function canMutateOrganization(
        organization: Organization,
    ): boolean {
        return !isProtectedOrganization(
            organization,
        )
    }

    return (
        <div className="page">
            <h1>Организации</h1>

            {mutationError && (
                <div
                    className="error"
                    role="alert"
                    aria-live="assertive"
                >
                    {mutationError}
                </div>
            )}

            {success && (
                <div
                    className="success"
                    role="status"
                    aria-live="polite"
                >
                    {success}
                </div>
            )}

            <div className="card form-card">
                <h2>Создать организацию</h2>

                <form
                    className="form"
                    onSubmit={
                        submitCreateOrganization
                    }
                >
                    <label>
                        Название организации
                        <input
                            value={name}
                            onChange={(event) =>
                                setName(
                                    event.target.value,
                                )
                            }
                            placeholder="Demo Company"
                            maxLength={255}
                            required
                            disabled={
                                creating
                                || hasPendingAction
                            }
                        />
                    </label>

                    <button
                        type="submit"
                        disabled={
                            creating
                            || hasPendingAction
                            || !name.trim()
                        }
                    >
                        {creating
                            ? 'Создание...'
                            : 'Создать организацию'}
                    </button>
                </form>
            </div>

            {loading && (
                <LoadingState
                    message="Загрузка организаций..."
                />
            )}

            {!loading && loadError && (
                <ErrorState
                    title="Ошибка загрузки организаций"
                    message={loadError}
                    action={
                        <button
                            type="button"
                            onClick={() =>
                                setReloadToken(
                                    (value) =>
                                        value + 1,
                                )
                            }
                        >
                            Повторить
                        </button>
                    }
                />
            )}

            {!loading
                && !loadError
                && organizations.length === 0
                && (
                    <EmptyState
                        message={
                            'Организации не найдены.'
                        }
                    />
                )}

            {!loading
                && !loadError
                && organizations.length > 0
                && (
                    <div className="card table-card">
                        <div className="admin-table-wrapper">
                            <table className="admin-table">
                                <thead>
                                    <tr>
                                        <th>
                                            Название
                                        </th>
                                        <th>ID</th>
                                        <th>Тип</th>
                                        <th>
                                            Статус
                                        </th>
                                        <th>
                                            Версия
                                        </th>
                                        <th>
                                            Создана
                                        </th>
                                        <th>
                                            Действия
                                        </th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {organizations.map(
                                        (
                                            organization,
                                        ) => {
                                            const mutable =
                                                canMutateOrganization(
                                                    organization,
                                                )

                                            return (
                                                <tr
                                                    key={
                                                        organization.id
                                                    }
                                                    className={
                                                        !organization.enabled
                                                            ? 'organizations-table__row--disabled'
                                                            : organization.type === 'PLATFORM'
                                                                ? 'organizations-table__row--platform'
                                                                : undefined
                                                    }
                                                >
                                                    <td>
                                                        {
                                                            organization.name
                                                        }
                                                    </td>
                                                    <td>
                                                        {
                                                            organization.id
                                                        }
                                                    </td>
                                                    <td>
                                                        <OrganizationTypeBadge
                                                            type={
                                                                organization.type
                                                            }
                                                        />
                                                    </td>
                                                    <td>
                                                        <OrganizationStatusBadge
                                                            enabled={
                                                                organization.enabled
                                                            }
                                                        />
                                                    </td>
                                                    <td>
                                                        <span className="organization-version">
                                                            v{organization.version}
                                                        </span>
                                                    </td>
                                                    <td>
                                                        {
                                                            formatDateTime(
                                                                organization.createdAt,
                                                            )
                                                        }
                                                    </td>
                                                    <td className="actions-cell">
                                                        <div className="organization-actions">
                                                            <button
                                                                type="button"
                                                                className="secondary-button"
                                                                disabled={
                                                                    detailsLoadingId
                                                                    === organization.id
                                                                }
                                                                onClick={() =>
                                                                    void openDetailsModal(
                                                                        organization,
                                                                    )
                                                                }
                                                            >
                                                                {detailsLoadingId
                                                                    === organization.id
                                                                    ? 'Загрузка...'
                                                                    : 'Подробнее'}
                                                            </button>
                                                        </div>

                                                        {!mutable
                                                            ? (
                                                                <span className="muted">
                                                                    {
                                                                        getProtectionLabel(
                                                                            organization,
                                                                        )
                                                                    }
                                                                </span>
                                                            )
                                                            : (
                                                                <div className="table-actions">
                                                                    <button
                                                                        type="button"
                                                                        className="secondary-button"
                                                                        disabled={
                                                                            hasPendingAction
                                                                        }
                                                                        onClick={() =>
                                                                            openRenameModal(
                                                                                organization,
                                                                            )
                                                                        }
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
                                                                        disabled={
                                                                            hasPendingAction
                                                                            || impactLoadingId
                                                                                === organization.id
                                                                        }
                                                                        onClick={() => {
                                                                            if (
                                                                                organization.enabled
                                                                            ) {
                                                                                void openDisableDialog(
                                                                                    organization,
                                                                                )
                                                                            } else {
                                                                                setEnableOrganizationTarget(
                                                                                    organization,
                                                                                )
                                                                            }
                                                                        }}
                                                                    >
                                                                        {impactLoadingId
                                                                            === organization.id
                                                                            ? 'Проверка...'
                                                                            : organization.enabled
                                                                                ? 'Отключить'
                                                                                : 'Включить'}
                                                                    </button>
                                                                </div>
                                                            )}
                                                    </td>
                                                </tr>
                                            )
                                        },
                                    )}
                                </tbody>
                            </table>
                        </div>

                        <div className="pagination">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={
                                    page === 0
                                    || loading
                                    || hasPendingAction
                                }
                                onClick={() =>
                                    setPage(
                                        (value) =>
                                            Math.max(
                                                0,
                                                value - 1,
                                            ),
                                    )
                                }
                            >
                                Назад
                            </button>

                            <span>
                                Страница
                                {' '}
                                {page + 1}
                                {' '}
                                из
                                {' '}
                                {Math.max(
                                    totalPages,
                                    1,
                                )}
                            </span>

                            <button
                                type="button"
                                className="secondary-button"
                                disabled={
                                    page + 1
                                        >= totalPages
                                    || loading
                                    || hasPendingAction
                                }
                                onClick={() =>
                                    setPage(
                                        (value) =>
                                            value + 1,
                                    )
                                }
                            >
                                Вперёд
                            </button>
                        </div>
                    </div>
                )}

            {detailsOrganization && (
                <Modal
                    title="Подробнее об организации"
                    onClose={
                        closeDetailsModal
                    }
                    size="md"
                >
                    {detailsError && (
                        <div
                            className="error"
                            role="alert"
                        >
                            {detailsError}
                        </div>
                    )}

                    <div className="organization-details-heading">
                        <div>
                            <strong>
                                {detailsOrganization.name}
                            </strong>
                            <span>
                                {detailsOrganization.id}
                            </span>
                        </div>

                        <OrganizationStatusBadge
                            enabled={
                                detailsOrganization.enabled
                            }
                        />
                    </div>

                    <dl className="organization-details">
                        <OrganizationDetail
                            term="Тип"
                            value={
                                <OrganizationTypeBadge
                                    type={
                                        detailsOrganization.type
                                    }
                                />
                            }
                        />
                        <OrganizationDetail
                            term="Защита"
                            value={
                                detailsOrganization.protected
                                    ? 'Защищённая системная организация'
                                    : 'Обычная клиентская организация'
                            }
                        />
                        <OrganizationDetail
                            term="Версия"
                            value={
                                `v${detailsOrganization.version}`
                            }
                        />
                        <OrganizationDetail
                            term="Дата создания"
                            value={
                                formatDateTime(
                                    detailsOrganization.createdAt,
                                )
                            }
                        />
                        <OrganizationDetail
                            term="Последнее изменение"
                            value={
                                detailsOrganization.updatedAt
                                    ? formatDateTime(
                                        detailsOrganization.updatedAt,
                                    )
                                    : '—'
                            }
                        />
                    </dl>

                    {detailsOrganization.type
                        === 'PLATFORM'
                        && (
                            <div className="organization-platform-note">
                                PLATFORM используется самой платформой
                                SafeAI Desk и защищена от обычных
                                переименований, включения и отключения.
                            </div>
                        )}
                </Modal>
            )}

            {renameOrganization && (
                <Modal
                    title={
                        'Переименовать организацию: '
                        + renameOrganization.name
                    }
                    onClose={closeRenameModal}
                    closeDisabled={
                        hasPendingAction
                    }
                >
                    <form
                        className="form"
                        onSubmit={
                            submitRenameOrganization
                        }
                    >
                        <label>
                            Новое название
                            <input
                                value={renameValue}
                                onChange={(event) =>
                                    setRenameValue(
                                        event.target.value,
                                    )
                                }
                                maxLength={255}
                                required
                                autoFocus
                                disabled={
                                    hasPendingAction
                                }
                            />
                        </label>

                        {renameError && (
                            <div
                                className="error"
                                role="alert"
                                aria-live="assertive"
                            >
                                {renameError}
                            </div>
                        )}

                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={
                                    hasPendingAction
                                }
                                onClick={
                                    closeRenameModal
                                }
                            >
                                Отмена
                            </button>

                            <button
                                type="submit"
                                disabled={
                                    hasPendingAction
                                    || !renameValue.trim()
                                }
                            >
                                {hasPendingAction
                                    ? 'Сохранение...'
                                    : 'Сохранить'}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {disableDialog && (
                <Modal
                    title="Отключить организацию"
                    onClose={() => {
                        if (!hasPendingAction) {
                            setDisableDialog(null)
                        }
                    }}
                    closeDisabled={
                        hasPendingAction
                    }
                    size="sm"
                >
                    <p>
                        Организация:
                        {' '}
                        <strong>
                            {
                                disableDialog.organization.name
                            }
                        </strong>
                    </p>

                    <dl className="user-details">
                        <Impact
                            term="Включённых пользователей"
                            value={
                                disableDialog.impact.enabledUsers
                            }
                        />
                        <Impact
                            term="Администраторов"
                            value={
                                disableDialog.impact.administrators
                            }
                        />
                        <Impact
                            term="Активных refresh-сессий"
                            value={
                                disableDialog.impact.activeRefreshSessions
                            }
                        />
                        <Impact
                            term="Активных операций чата"
                            value={
                                disableDialog.impact.activeChatOperations
                            }
                        />
                    </dl>

                    <div className="danger-notice">
                        Будет запущено отключение
                        организации и отзыв активных
                        сессий. Операция может занять
                        некоторое время.
                    </div>

                    <form
                        className="form"
                        onSubmit={
                            confirmDisableOrganization
                        }
                    >
                        <label>
                            Введите название организации
                            для подтверждения
                            <input
                                value={
                                    disableDialog.confirmationName
                                }
                                onChange={(event) =>
                                    setDisableDialog(
                                        (current) =>
                                            current
                                                ? {
                                                    ...current,
                                                    confirmationName:
                                                        event.target.value,
                                                }
                                                : current,
                                    )
                                }
                                autoComplete="off"
                                required
                                disabled={
                                    hasPendingAction
                                }
                            />

                            <span className="organization-confirmation-hint">
                                Регистр, вид кавычек и лишние
                                пробелы не учитываются. Например:
                                {' '}
                                <strong>
                                    ООО &quot;Зил&quot;
                                </strong>
                                {' '}
                                можно ввести как
                                {' '}
                                <strong>
                                    ооо зил
                                </strong>
                                {' '}
                                или
                                {' '}
                                <strong>
                                    ООО «ЗИЛ»
                                </strong>
                                . При этом ООО/АО и само
                                название должны совпадать
                                без опечаток.
                            </span>
                        </label>

                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={
                                    hasPendingAction
                                }
                                onClick={() =>
                                    setDisableDialog(
                                        null,
                                    )
                                }
                            >
                                Отмена
                            </button>

                            <button
                                type="submit"
                                className="danger-button"
                                disabled={
                                    hasPendingAction
                                    || normalizeOrganizationConfirmation(
                                        disableDialog.confirmationName,
                                    )
                                        !== normalizeOrganizationConfirmation(
                                            disableDialog.organization.name,
                                        )
                                }
                            >
                                {hasPendingAction
                                    ? 'Отключение...'
                                    : 'Отключить организацию'}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {enableOrganizationTarget && (
                <Modal
                    title="Включить организацию"
                    onClose={() => {
                        if (!hasPendingAction) {
                            setEnableOrganizationTarget(
                                null,
                            )
                        }
                    }}
                    closeDisabled={
                        hasPendingAction
                    }
                    size="sm"
                >
                    <p>
                        Включить организацию
                        {' '}
                        <strong>
                            {
                                enableOrganizationTarget.name
                            }
                        </strong>
                        ?
                    </p>

                    <div className="modal-actions">
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={
                                hasPendingAction
                            }
                            onClick={() =>
                                setEnableOrganizationTarget(
                                    null,
                                )
                            }
                        >
                            Отмена
                        </button>

                        <button
                            type="button"
                            disabled={
                                hasPendingAction
                            }
                            onClick={() =>
                                void confirmEnableOrganization()
                            }
                        >
                            {hasPendingAction
                                ? 'Включение...'
                                : 'Включить'}
                        </button>
                    </div>
                </Modal>
            )}
        </div>
    )
}

function Impact({
    term,
    value,
}: {
    term: string
    value: number
}) {
    return (
        <div className="user-details__row">
            <dt>{term}</dt>
            <dd>{value}</dd>
        </div>
    )
}

function getProtectionError(
    organization: Organization,
): string {
    return isProtectedOrganization(
        organization,
    )
        ? (
            'Платформенная организация защищена '
            + 'и не изменяется через обычный '
            + 'organization-management.'
        )
        : 'Организация недоступна для изменения.'
}

function getProtectionLabel(
    organization: Organization,
): string {
    return isProtectedOrganization(
        organization,
    )
        ? 'Защищённая системная организация'
        : 'Недоступно'
}

function OrganizationStatusBadge({
    enabled,
}: {
    enabled: boolean
}) {
    return (
        <span
            className={
                enabled
                    ? 'organization-status organization-status--enabled'
                    : 'organization-status organization-status--disabled'
            }
        >
            <span
                className="organization-status__dot"
                aria-hidden="true"
            />
            {enabled
                ? 'Включена'
                : 'Отключена'}
        </span>
    )
}

function OrganizationTypeBadge({
    type,
}: {
    type: Organization['type']
}) {
    return (
        <span
            className={
                type === 'PLATFORM'
                    ? 'organization-type organization-type--platform'
                    : 'organization-type organization-type--tenant'
            }
            title={type}
        >
            {type === 'PLATFORM'
                ? 'Платформенная'
                : 'Клиентская'}
        </span>
    )
}

function OrganizationDetail({
    term,
    value,
}: {
    term: string
    value: ReactNode
}) {
    return (
        <div className="organization-details__row">
            <dt>{term}</dt>
            <dd>{value}</dd>
        </div>
    )
}

function isVersionConflict(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && (
            error.status === 409
            || error.status === 412
        )
        && (
            error.errorCode
                === 'ORGANIZATION_VERSION_CONFLICT'
            || error.errorCode
                === 'OPTIMISTIC_LOCK_CONFLICT'
        )
}

function isRequestAborted(
    error: unknown,
): boolean {
    return error instanceof ApiError
        && error.errorCode
            === 'REQUEST_ABORTED'
}

export default AdminOrganizationsPage

import type {
    Organization,
} from '../../../api/organizationApi'
import {
    formatDateTime,
} from '../../../utils/format'
import {
    EmptyState,
    ErrorState,
    LoadingState,
} from '../../StateBlock'
import {
    canMutateOrganization,
    getProtectionLabel,
} from '../../../pages/adminOrganizationsSupport'
import {
    OrganizationStatusBadge,
    OrganizationTypeBadge,
} from './AdminOrganizationsUi'

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

export function AdminOrganizationsView({
    organizations,
    loadError,
    mutationError,
    createError,
    success,
    loading,
    creating,
    hasPendingAction,
    page,
    totalPages,
    name,
    detailsLoadingId,
    impactLoadingId,
    onNameChange,
    onCreate,
    onReload,
    onDetails,
    onRename,
    onDisable,
    onEnable,
    onPageChange,
}: AdminOrganizationsViewProps) {
    return (
        <>
            <header className="organizations-page__header">
                <div>
                    <span className="organizations-page__eyebrow">
                        TENANT MANAGEMENT
                    </span>
                    <h1>Организации</h1>
                    <p>
                        Управляйте tenant-организациями, их статусом
                        и административным жизненным циклом.
                    </p>
                </div>

                <div className="organizations-page__summary">
                    <span>На странице</span>
                    <strong>{organizations.length}</strong>
                    <small>
                        Страница {page + 1} из {Math.max(totalPages, 1)}
                    </small>
                </div>
            </header>

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

            <section className="card organizations-create-card">
                <div className="organizations-section-heading">
                    <div>
                        <span>Новая организация</span>
                        <h2>Создать tenant</h2>
                    </div>
                    <p>
                        PLATFORM создаётся и защищается backend’ом;
                        через этот интерфейс создаются клиентские организации.
                    </p>
                </div>

                <form
                    className="organizations-create-form"
                    onSubmit={(event) => {
                        event.preventDefault()
                        void onCreate()
                    }}
                >
                    <label>
                        Название организации
                        <input
                            value={name}
                            onChange={(event) =>
                                onNameChange(
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

                {createError && (
                    <div
                        className="error organizations-create-error"
                        role="alert"
                    >
                        {createError}
                    </div>
                )}
            </section>

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
                            onClick={onReload}
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
                        message="Организации не найдены."
                    />
                )}

            {!loading
                && !loadError
                && organizations.length > 0
                && (
                    <section className="card organizations-table-card">
                        <div className="organizations-table-scroll">
                            <table className="admin-table organizations-table">
                                <thead>
                                    <tr>
                                        <th>Название</th>
                                        <th>ID</th>
                                        <th>Тип</th>
                                        <th>Статус</th>
                                        <th>Версия</th>
                                        <th>Создана</th>
                                        <th>Действия</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {organizations.map(
                                        (organization) => {
                                            const mutable =
                                                canMutateOrganization(
                                                    organization,
                                                )

                                            return (
                                                <tr
                                                    key={organization.id}
                                                    className={
                                                        !organization.enabled
                                                            ? 'organizations-table__row--disabled'
                                                            : organization.type === 'PLATFORM'
                                                                ? 'organizations-table__row--platform'
                                                                : undefined
                                                    }
                                                >
                                                    <td>
                                                        <strong className="organization-name-cell">
                                                            {organization.name}
                                                        </strong>
                                                    </td>
                                                    <td>
                                                        <code className="organization-id-cell">
                                                            {organization.id}
                                                        </code>
                                                    </td>
                                                    <td>
                                                        <OrganizationTypeBadge
                                                            type={organization.type}
                                                        />
                                                    </td>
                                                    <td>
                                                        <OrganizationStatusBadge
                                                            enabled={organization.enabled}
                                                        />
                                                    </td>
                                                    <td>
                                                        <span className="organization-version">
                                                            v{organization.version}
                                                        </span>
                                                    </td>
                                                    <td>
                                                        {formatDateTime(
                                                            organization.createdAt,
                                                        )}
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
                                                                    void onDetails(
                                                                        organization,
                                                                    )
                                                                }
                                                            >
                                                                {detailsLoadingId
                                                                    === organization.id
                                                                    ? 'Загрузка...'
                                                                    : 'Подробнее'}
                                                            </button>

                                                            {mutable && (
                                                                <button
                                                                    type="button"
                                                                    className="secondary-button"
                                                                    disabled={hasPendingAction}
                                                                    onClick={() =>
                                                                        onRename(
                                                                            organization,
                                                                        )
                                                                    }
                                                                >
                                                                    Переименовать
                                                                </button>
                                                            )}

                                                            {mutable && (
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
                                                                        if (organization.enabled) {
                                                                            void onDisable(
                                                                                organization,
                                                                            )
                                                                        } else {
                                                                            onEnable(
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
                                                            )}
                                                        </div>

                                                        {!mutable && (
                                                            <span className="organization-protection-label">
                                                                {getProtectionLabel(
                                                                    organization,
                                                                )}
                                                            </span>
                                                        )}
                                                    </td>
                                                </tr>
                                            )
                                        },
                                    )}
                                </tbody>
                            </table>
                        </div>

                        <div className="pagination organizations-pagination">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={
                                    page === 0
                                    || loading
                                    || hasPendingAction
                                }
                                onClick={() =>
                                    onPageChange(
                                        Math.max(0, page - 1),
                                    )
                                }
                            >
                                Назад
                            </button>

                            <span>
                                Страница {page + 1} из {Math.max(totalPages, 1)}
                            </span>

                            <button
                                type="button"
                                className="secondary-button"
                                disabled={
                                    page + 1 >= totalPages
                                    || loading
                                    || hasPendingAction
                                }
                                onClick={() =>
                                    onPageChange(page + 1)
                                }
                            >
                                Вперёд
                            </button>
                        </div>
                    </section>
                )}
        </>
    )
}

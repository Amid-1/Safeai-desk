import type { User, UserDetails } from '../../../api/userApi'
import type { OrganizationDirectoryItem } from '../../../api/organizationApi'
import type { AssignableRole } from '../../../pages/adminUsersSupport'
import { formatDateTime } from '../../../utils/format'
import Modal from '../../Modal'
import ConfirmDialog from '../../ConfirmDialog'
import { FixedUserRole, UserRoleSelector } from '../UserRoleSelector'
import {
    Detail,
    ModalActions,
    ModalError,
    PasswordFields,
    UserStatusBadge,
} from './AdminUsersUi'
import {
    findOrganizationName,
    getRoleLabel,
    normalizeEmail,
} from '../../../pages/adminUsersSupport'

type ConfirmState = {
    user: User
    nextEnabled: boolean
} | null

export type AdminUsersDialogsProps = {
    createModalOpen: boolean
    createError: string
    email: string
    password: string
    passwordConfirm: string
    fullName: string
    createRole: AssignableRole
    currentUserIsSuperAdmin: boolean
    organizationsLoading: boolean
    organizations: OrganizationDirectoryItem[]
    selectedOrganizationId: string
    creating: boolean
    detailsUser: UserDetails | null
    detailsError: string
    editUser: User | null
    editEmail: string
    editFullName: string
    rolesUser: User | null
    selectedRole: AssignableRole
    adminElevationConfirmed: boolean
    resetPasswordUser: User | null
    resetPasswordValue: string
    resetPasswordConfirm: string
    deleteUser: User | null
    deleteConfirmationEmail: string
    modalError: string
    hasPendingMutation: boolean
    confirmState: ConfirmState
    setEmail: (value: string) => void
    setPassword: (value: string) => void
    setPasswordConfirm: (value: string) => void
    setFullName: (value: string) => void
    setCreateRole: (value: AssignableRole) => void
    setSelectedOrganizationId: (value: string) => void
    setEditEmail: (value: string) => void
    setEditFullName: (value: string) => void
    setSelectedRole: (value: AssignableRole) => void
    setAdminElevationConfirmed: (value: boolean) => void
    setResetPasswordValue: (value: string) => void
    setResetPasswordConfirm: (value: string) => void
    setDeleteConfirmationEmail: (value: string) => void
    setConfirmState: (value: ConfirmState) => void
    handleCreateUser: () => void | Promise<void>
    closeCreateModal: () => void
    closeDetailsModal: () => void
    closeMutationModals: () => void
    submitEditUser: () => void | Promise<void>
    submitRoles: () => void | Promise<void>
    submitResetPassword: () => void | Promise<void>
    submitPermanentDelete: () => void | Promise<void>
    confirmEnabledChange: () => void | Promise<void>
}

export function AdminUsersDialogs(props: AdminUsersDialogsProps) {
    const {
        createModalOpen, createError, email, password, passwordConfirm, fullName, createRole,
        currentUserIsSuperAdmin, organizationsLoading, organizations, selectedOrganizationId, creating,
        detailsUser, detailsError, editUser, editEmail, editFullName, rolesUser, selectedRole,
        adminElevationConfirmed, resetPasswordUser, resetPasswordValue, resetPasswordConfirm,
        deleteUser, deleteConfirmationEmail, modalError, hasPendingMutation, confirmState,
        setEmail, setPassword, setPasswordConfirm, setFullName, setCreateRole, setSelectedOrganizationId,
        setEditEmail, setEditFullName, setSelectedRole, setAdminElevationConfirmed, setResetPasswordValue,
        setResetPasswordConfirm, setDeleteConfirmationEmail, setConfirmState, handleCreateUser, closeCreateModal,
        closeDetailsModal, closeMutationModals, submitEditUser, submitRoles, submitResetPassword,
        submitPermanentDelete, confirmEnabledChange,
    } = props

    return (
        <>
            {createModalOpen && (
                <Modal
                    title="Создать пользователя"
                    onClose={closeCreateModal}
                    closeDisabled={creating}
                    size="md"
                >
                    <form
                        className="form users-create-form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void handleCreateUser()
                        }}
                    >
                        <div className="users-create-form__grid">
                            <label>
                                Email
                                <input
                                    value={email}
                                    onChange={(event) =>
                                        setEmail(
                                            event.target.value,
                                        )
                                    }
                                    type="email"
                                    autoComplete="username"
                                    maxLength={255}
                                    placeholder="user@company.ru"
                                    required
                                    disabled={creating}
                                />
                            </label>

                            <label>
                                Полное имя
                                <input
                                    value={fullName}
                                    onChange={(event) =>
                                        setFullName(
                                            event.target.value,
                                        )
                                    }
                                    maxLength={255}
                                    placeholder="Иван Иванов"
                                    disabled={creating}
                                />
                            </label>

                            <PasswordFields
                                password={password}
                                passwordConfirm={
                                    passwordConfirm
                                }
                                onPasswordChange={
                                    setPassword
                                }
                                onPasswordConfirmChange={
                                    setPasswordConfirm
                                }
                                disabled={creating}
                            />

                            {currentUserIsSuperAdmin && (
                                <label className="users-create-form__wide">
                                    Организация
                                    <select
                                        value={
                                            selectedOrganizationId
                                        }
                                        onChange={(event) =>
                                            setSelectedOrganizationId(
                                                event.target.value,
                                            )
                                        }
                                        disabled={
                                            creating
                                            || organizationsLoading
                                        }
                                        required
                                    >
                                        <option value="">
                                            {organizationsLoading
                                                ? 'Загрузка организаций...'
                                                : 'Выберите организацию'}
                                        </option>
                                        {organizations.map(
                                            (
                                                organization,
                                            ) => (
                                                <option
                                                    key={
                                                        organization.id
                                                    }
                                                    value={
                                                        organization.id
                                                    }
                                                >
                                                    {
                                                        organization.name
                                                    }
                                                </option>
                                            ),
                                        )}
                                    </select>
                                </label>
                            )}
                        </div>

                        {currentUserIsSuperAdmin
                            ? (
                                <>
                                    <UserRoleSelector
                                        name="create-user-role"
                                        value={createRole}
                                        disabled={creating}
                                        onChange={
                                            setCreateRole
                                        }
                                        legend="Роль пользователя"
                                    />

                                    {createRole === 'ADMIN' && (
                                        <div
                                            className="users-privilege-notice"
                                            role="note"
                                        >
                                            Администратор сможет управлять
                                            пользователями выбранной организации,
                                            но не сможет создавать других
                                            администраторов.
                                        </div>
                                    )}
                                </>
                            )
                            : (
                                <FixedUserRole
                                    userRole="USER"
                                    title="Роль пользователя"
                                    description={
                                        'Пользователь будет создан '
                                        + 'в вашей организации.'
                                    }
                                />
                            )}

                        {createError && (
                            <div
                                className="error"
                                role="alert"
                                aria-live="assertive"
                            >
                                {createError}
                            </div>
                        )}

                        <div className="modal-actions">
                            <button
                                type="button"
                                className={
                                    'secondary-button'
                                }
                                disabled={creating}
                                onClick={
                                    closeCreateModal
                                }
                            >
                                Отмена
                            </button>
                            <button
                                type="submit"
                                disabled={
                                    creating
                                    || organizationsLoading
                                }
                            >
                                {creating
                                    ? 'Создание...'
                                    : (
                                        'Создать '
                                        + 'пользователя'
                                    )}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {detailsUser && (
                <Modal
                    title="Подробнее о пользователе"
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

                    <dl className="user-details">
                        <Detail
                            term="Email"
                            value={
                                detailsUser.email
                            }
                        />
                        <Detail
                            term="Полное имя"
                            value={
                                detailsUser.fullName
                                ?? '—'
                            }
                        />
                        <Detail
                            term="Организация"
                            value={
                                <div className="user-details__organization">
                                    <strong className="user-details__organization-name">
                                        {
                                            detailsUser.organizationName
                                            ?? findOrganizationName(
                                                detailsUser.organizationId,
                                                organizations,
                                            )
                                            ?? 'Название недоступно'
                                        }
                                    </strong>

                                    <span className="user-details__organization-id">
                                        {detailsUser.organizationId}
                                    </span>
                                </div>
                            }
                        />
                        <Detail
                            term="Роли"
                            value={
                                detailsUser.roles
                                    .map(
                                        getRoleLabel,
                                    )
                                    .join(', ')
                            }
                        />
                        <Detail
                            term="Статус"
                            value={
                                <UserStatusBadge
                                    enabled={
                                        detailsUser.enabled
                                    }
                                />
                            }
                        />
                        <Detail
                            term="Версия"
                            value={
                                detailsUser.version
                                    ?.toString()
                                ?? 'не предоставлена'
                            }
                        />
                        <Detail
                            term="Дата создания"
                            value={
                                formatDateTime(
                                    detailsUser.createdAt,
                                )
                            }
                        />
                        <Detail
                            term="Последнее изменение"
                            value={
                                formatDateTime(
                                    detailsUser.updatedAt,
                                )
                            }
                        />
                        <Detail
                            term="Последний вход"
                            value={
                                detailsUser.lastLoginAt
                                    ? formatDateTime(
                                        detailsUser.lastLoginAt,
                                    )
                                    : 'Ещё не входил'
                            }
                        />
                    </dl>
                </Modal>
            )}

            {editUser && (
                <Modal
                    title="Редактирование пользователя"
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <form
                        className="form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void submitEditUser()
                        }}
                    >
                        <label>
                            Email
                            <input
                                value={editEmail}
                                onChange={(event) =>
                                    setEditEmail(
                                        event.target.value,
                                    )
                                }
                                type="email"
                                maxLength={255}
                                required
                                disabled={
                                    hasPendingMutation
                                }
                            />
                        </label>

                        <label>
                            Полное имя
                            <input
                                value={editFullName}
                                onChange={(event) =>
                                    setEditFullName(
                                        event.target.value,
                                    )
                                }
                                maxLength={255}
                                disabled={
                                    hasPendingMutation
                                }
                            />
                        </label>

                        <ModalError
                            message={modalError}
                        />

                        <ModalActions
                            busy={
                                hasPendingMutation
                            }
                            onCancel={
                                closeMutationModals
                            }
                            submitLabel={
                                'Сохранить изменения'
                            }
                        />
                    </form>
                </Modal>
            )}

            {rolesUser && currentUserIsSuperAdmin && (
                <Modal
                    title="Роли и доступ"
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <form
                        className="form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void submitRoles()
                        }}
                    >
                        <p className="modal-subtitle">
                            {rolesUser.email}
                        </p>

                        <UserRoleSelector
                            name="edit-user-role"
                            value={selectedRole}
                            disabled={
                                hasPendingMutation
                            }
                            onChange={(role) => {
                                setSelectedRole(role)
                                setAdminElevationConfirmed(
                                    false,
                                )
                            }}
                            legend="Системная роль"
                        />

                        {selectedRole === 'ADMIN'
                            && !rolesUser.roles.includes(
                                'ADMIN',
                            )
                            && (
                                <label className="danger-notice">
                                    <input
                                        type="checkbox"
                                        checked={
                                            adminElevationConfirmed
                                        }
                                        onChange={(
                                            event,
                                        ) =>
                                            setAdminElevationConfirmed(
                                                event.target.checked,
                                            )
                                        }
                                        disabled={
                                            hasPendingMutation
                                        }
                                    />
                                    Подтверждаю повышение
                                    привилегий до ADMIN.
                                    Пользователь получит
                                    административный доступ.
                                </label>
                            )}

                        <ModalError
                            message={modalError}
                        />

                        <ModalActions
                            busy={
                                hasPendingMutation
                            }
                            onCancel={
                                closeMutationModals
                            }
                            submitLabel={
                                'Сохранить изменения'
                            }
                        />
                    </form>
                </Modal>
            )}

            {resetPasswordUser && (
                <Modal
                    title="Установить новый пароль"
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <form
                        className="form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void submitResetPassword()
                        }}
                    >
                        <PasswordFields
                            password={
                                resetPasswordValue
                            }
                            passwordConfirm={
                                resetPasswordConfirm
                            }
                            onPasswordChange={
                                setResetPasswordValue
                            }
                            onPasswordConfirmChange={
                                setResetPasswordConfirm
                            }
                            disabled={
                                hasPendingMutation
                            }
                            passwordLabel={
                                'Новый пароль'
                            }
                        />

                        <ModalError
                            message={modalError}
                        />

                        <ModalActions
                            busy={
                                hasPendingMutation
                            }
                            onCancel={
                                closeMutationModals
                            }
                            submitLabel={
                                'Установить новый пароль'
                            }
                        />
                    </form>
                </Modal>
            )}

            {deleteUser && (
                <Modal
                    title="Удалить пользователя навсегда?"
                    size="sm"
                    onClose={
                        closeMutationModals
                    }
                    closeDisabled={
                        hasPendingMutation
                    }
                >
                    <div className="danger-notice">
                        Пользователь должен быть
                        предварительно отключён.
                        Backend дополнительно проверяет
                        зависимости, retention policy,
                        last-admin invariant и защиту
                        платформенной организации.
                    </div>

                    <form
                        className="form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void submitPermanentDelete()
                        }}
                    >
                        <label>
                            Введите email пользователя
                            <input
                                type="email"
                                value={
                                    deleteConfirmationEmail
                                }
                                onChange={(event) =>
                                    setDeleteConfirmationEmail(
                                        event.target.value,
                                    )
                                }
                                autoComplete="off"
                                required
                                disabled={
                                    hasPendingMutation
                                }
                            />
                        </label>

                        <ModalError
                            message={modalError}
                        />

                        <ModalActions
                            busy={
                                hasPendingMutation
                            }
                            onCancel={
                                closeMutationModals
                            }
                            submitLabel={
                                'Удалить навсегда'
                            }
                            danger
                            submitDisabled={
                                normalizeEmail(
                                    deleteConfirmationEmail,
                                )
                                !== normalizeEmail(
                                    deleteUser.email,
                                )
                            }
                        />
                    </form>
                </Modal>
            )}

            {confirmState && (
                <ConfirmDialog
                    title={
                        confirmState.nextEnabled
                            ? 'Включить пользователя'
                            : 'Отключить пользователя'
                    }
                    message={
                        confirmState.nextEnabled
                            ? (
                                'Включить пользователя '
                                + `${confirmState.user.email}?`
                            )
                            : (
                                'Отключить пользователя '
                                + `${confirmState.user.email}? `
                                + 'Будет запущен отзыв '
                                + 'активных сессий.'
                            )
                    }
                    confirmText={
                        confirmState.nextEnabled
                            ? 'Включить пользователя'
                            : 'Отключить пользователя'
                    }
                    danger={
                        !confirmState.nextEnabled
                    }
                    loading={
                        hasPendingMutation
                    }
                    onCancel={() => {
                        if (!hasPendingMutation) {
                            setConfirmState(null)
                        }
                    }}
                    onConfirm={
                        confirmEnabledChange
                    }
                />
            )}
        </>
    )
}

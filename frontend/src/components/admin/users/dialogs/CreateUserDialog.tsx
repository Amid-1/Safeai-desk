// ============================================================
// frontend/src/components/admin/users/dialogs/CreateUserDialog.tsx
// ============================================================
import type { AdminUsersDialogsProps } from '../AdminUsersDialogs.types'
import Modal from '../../../Modal'
import { PasswordFields } from '../AdminUsersUi'
import { UserRoleSelector, FixedUserRole } from '../../UserRoleSelector'

type CreateUserDialogProps = Pick<AdminUsersDialogsProps,
    | 'createModalOpen'
    | 'closeCreateModal'
    | 'creating'
    | 'handleCreateUser'
    | 'email'
    | 'setEmail'
    | 'fullName'
    | 'setFullName'
    | 'password'
    | 'passwordConfirm'
    | 'setPassword'
    | 'setPasswordConfirm'
    | 'currentUserIsSuperAdmin'
    | 'selectedOrganizationId'
    | 'setSelectedOrganizationId'
    | 'organizationsLoading'
    | 'organizations'
    | 'createRole'
    | 'setCreateRole'
    | 'createError'
>

export function CreateUserDialog(props: CreateUserDialogProps) {
    const { createModalOpen, closeCreateModal, creating, handleCreateUser, email, setEmail, fullName, setFullName, password, passwordConfirm, setPassword, setPasswordConfirm, currentUserIsSuperAdmin, selectedOrganizationId, setSelectedOrganizationId, organizationsLoading, organizations, createRole, setCreateRole, createError } = props
    return (
        <>
            {createModalOpen && (
                <Modal
                    title={
                        'Создать пользователя'
                    }
                    onClose={
                        closeCreateModal
                    }
                    closeDisabled={
                        creating
                    }
                    size="md"
                >
                    <form
                        className={
                            'form users-create-form'
                        }
                        onSubmit={(event) => {
                            event.preventDefault()
                            void handleCreateUser()
                        }}
                    >
                        <div
                            className={
                                'users-create-form__grid'
                            }
                        >
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
                                    placeholder={
                                        'user@company.ru'
                                    }
                                    required
                                    disabled={
                                        creating
                                    }
                                />
                            </label>

                            <label>
                                Полное имя
                                <input
                                    value={
                                        fullName
                                    }
                                    onChange={(event) =>
                                        setFullName(
                                            event.target.value,
                                        )
                                    }
                                    maxLength={255}
                                    placeholder={
                                        'Иван Иванов'
                                    }
                                    disabled={
                                        creating
                                    }
                                />
                            </label>

                            <PasswordFields
                                password={
                                    password
                                }
                                passwordConfirm={
                                    passwordConfirm
                                }
                                onPasswordChange={
                                    setPassword
                                }
                                onPasswordConfirmChange={
                                    setPasswordConfirm
                                }
                                disabled={
                                    creating
                                }
                            />

                            {currentUserIsSuperAdmin
                                && (
                                    <label
                                        className={
                                            'users-create-form__wide'
                                        }
                                    >
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
                                            <option
                                                value=""
                                            >
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
                                        name={
                                            'create-user-role'
                                        }
                                        value={
                                            createRole
                                        }
                                        disabled={
                                            creating
                                        }
                                        onChange={
                                            setCreateRole
                                        }
                                        legend={
                                            'Роль пользователя'
                                        }
                                    />

                                    {createRole
                                        === 'ADMIN'
                                        && (
                                            <div
                                                className={
                                                    'users-privilege-notice'
                                                }
                                                role="note"
                                            >
                                                Администратор сможет
                                                управлять пользователями
                                                выбранной организации,
                                                но не сможет создавать
                                                или управлять другими
                                                администраторами.
                                            </div>
                                        )}
                                </>
                            )
                            : (
                                <FixedUserRole
                                    userRole="USER"
                                    title={
                                        'Роль пользователя'
                                    }
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

                        <div
                            className={
                                'modal-actions'
                            }
                        >
                            <button
                                type="button"
                                className={
                                    'secondary-button'
                                }
                                disabled={
                                    creating
                                }
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
                                    : 'Создать пользователя'}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}
        </>
    )
}

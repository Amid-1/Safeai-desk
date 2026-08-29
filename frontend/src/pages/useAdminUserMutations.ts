import {
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    permanentlyDeleteUser,
    resetUserPassword,
    updateUser,
    updateUserEnabled,
    updateUserRoles,
} from '../api/userApi'
import type {
    User,
} from '../api/userApi'
import type {
    AuthUser,
} from '../api/authApi'
import {
    isProtectedOrganization,
} from '../api/organizationApi'
import type {
    OrganizationDirectoryItem,
} from '../api/organizationApi'
import {
    validatePassword,
} from '../utils/password'
import {
    useAutoClearMessage,
} from '../hooks/useAutoClearMessage'
import {
    getAssignableRole,
    getMutationErrorMessage,
    isVersionConflict,
    normalizeEmail,
    normalizeOptionalText,
} from './adminUsersSupport'
import type {
    AssignableRole,
} from './adminUsersSupport'

const SUCCESS_MESSAGE_TIMEOUT_MS = 4_000

type PendingMutation = {
    userId: string
    type:
        | 'EDIT'
        | 'ROLES'
        | 'RESET_PASSWORD'
        | 'ENABLED'
        | 'DELETE'
} | null

export type AdminUsersConfirmState = {
    user: User
    nextEnabled: boolean
} | null

type UseAdminUserMutationsOptions = {
    currentUser: AuthUser
    currentUserIsSuperAdmin: boolean
    organizations: OrganizationDirectoryItem[]
    requestReloadFromFirstPage: () => void
}

export function useAdminUserMutations({
    currentUser,
    currentUserIsSuperAdmin,
    organizations,
    requestReloadFromFirstPage,
}: UseAdminUserMutationsOptions) {
    const [mutationError, setMutationError] =
        useState('')
    const [modalError, setModalError] =
        useState('')
    const [success, setSuccess] =
        useState('')
    const [pendingMutation, setPendingMutation] =
        useState<PendingMutation>(null)

    const [editUser, setEditUser] =
        useState<User | null>(null)
    const [editEmail, setEditEmail] =
        useState('')
    const [editFullName, setEditFullName] =
        useState('')

    const [rolesUser, setRolesUser] =
        useState<User | null>(null)
    const [selectedRole, setSelectedRole] =
        useState<AssignableRole>('USER')
    const [
        adminElevationConfirmed,
        setAdminElevationConfirmed,
    ] = useState(false)

    const [
        resetPasswordUser,
        setResetPasswordUser,
    ] = useState<User | null>(null)
    const [
        resetPasswordValue,
        setResetPasswordValue,
    ] = useState('')
    const [
        resetPasswordConfirm,
        setResetPasswordConfirm,
    ] = useState('')

    const [confirmState, setConfirmState] =
        useState<AdminUsersConfirmState>(null)

    const [deleteUser, setDeleteUser] =
        useState<User | null>(null)
    const [
        deleteConfirmationEmail,
        setDeleteConfirmationEmail,
    ] = useState('')

    const pendingMutationRef =
        useRef<PendingMutation>(null)

    const hasPendingMutation =
        pendingMutation !== null

    useEffect(() => {
        pendingMutationRef.current =
            pendingMutation
    }, [pendingMutation])

    useAutoClearMessage(
        success,
        setSuccess,
        SUCCESS_MESSAGE_TIMEOUT_MS,
    )

    function clearMutationSensitiveFields() {
        setResetPasswordValue('')
        setResetPasswordConfirm('')
        setDeleteConfirmationEmail('')
    }

    function closeMutationModals() {
        if (pendingMutationRef.current) {
            return
        }

        clearMutationSensitiveFields()
        setEditUser(null)
        setRolesUser(null)
        setResetPasswordUser(null)
        setDeleteUser(null)
        setModalError('')
        setAdminElevationConfirmed(false)
    }

    function ensureVersionAvailable(
        user: User,
    ): boolean {
        if (user.version !== null) {
            return true
        }

        setMutationError(
            'Backend не вернул version пользователя. '
            + 'Mutation заблокирована fail-closed, '
            + 'пока не реализован optimistic concurrency contract.',
        )
        return false
    }

    function requireVersion(
        user: User,
    ): number | null {
        if (user.version !== null) {
            return user.version
        }

        setModalError(
            'Backend не вернул version пользователя. '
            + 'Операция заблокирована.',
        )
        return null
    }

    function openEditModal(user: User) {
        if (!ensureVersionAvailable(user)) {
            return
        }

        closeMutationModals()
        setEditUser(user)
        setEditEmail(user.email)
        setEditFullName(user.fullName ?? '')
        setModalError('')
    }

    function openRolesModal(user: User) {
        if (!currentUserIsSuperAdmin) {
            setMutationError(
                'Только SUPER_ADMIN может изменять системную роль пользователя.',
            )
            return
        }

        if (!ensureVersionAvailable(user)) {
            return
        }

        const role = getAssignableRole(user)

        if (!role) {
            setMutationError(
                'Роль пользователя недоступна для изменения через user-management.',
            )
            return
        }

        closeMutationModals()
        setRolesUser(user)
        setSelectedRole(role)
        setAdminElevationConfirmed(false)
        setModalError('')
    }

    function openResetPasswordModal(
        user: User,
    ) {
        if (!ensureVersionAvailable(user)) {
            return
        }

        closeMutationModals()
        setResetPasswordUser(user)
        clearMutationSensitiveFields()
        setModalError('')
    }

    function openDeleteModal(user: User) {
        if (!ensureVersionAvailable(user)) {
            return
        }

        closeMutationModals()
        setDeleteUser(user)
        setDeleteConfirmationEmail('')
        setModalError('')
    }

    async function runUserMutation(
        mutation: Exclude<PendingMutation, null>,
        fallbackError: string,
        action: () => Promise<void>,
        modal = true,
    ) {
        if (pendingMutationRef.current) {
            return
        }

        pendingMutationRef.current = mutation
        setPendingMutation(mutation)

        if (modal) {
            setModalError('')
        } else {
            setMutationError('')
        }

        try {
            await action()
        } catch (error) {
            const message =
                getMutationErrorMessage(
                    error,
                    fallbackError,
                )

            if (modal) {
                setModalError(message)
            } else {
                setMutationError(message)
            }

            if (isVersionConflict(error)) {
                requestReloadFromFirstPage()
            }
        } finally {
            pendingMutationRef.current = null
            setPendingMutation(null)
        }
    }

    async function submitEditUser() {
        if (!editUser) {
            return
        }

        const expectedVersion =
            requireVersion(editUser)

        if (expectedVersion === null) {
            return
        }

        const normalizedEmail =
            normalizeEmail(editEmail)

        if (!normalizedEmail) {
            setModalError(
                'Введите email пользователя.',
            )
            return
        }

        await runUserMutation(
            {
                userId: editUser.id,
                type: 'EDIT',
            },
            'Не удалось обновить пользователя.',
            async () => {
                await updateUser(
                    editUser.id,
                    {
                        email: normalizedEmail,
                        fullName:
                            normalizeOptionalText(
                                editFullName,
                            ),
                        expectedVersion,
                    },
                )

                setEditUser(null)
                setSuccess(
                    `Пользователь ${normalizedEmail} обновлён.`,
                )
                requestReloadFromFirstPage()
            },
        )
    }

    async function submitRoles() {
        if (!rolesUser) {
            return
        }

        const expectedVersion =
            requireVersion(rolesUser)

        if (expectedVersion === null) {
            return
        }

        if (!currentUserIsSuperAdmin) {
            setModalError(
                'Только SUPER_ADMIN может изменять системную роль пользователя.',
            )
            return
        }

        const addingAdmin =
            selectedRole === 'ADMIN'
            && !rolesUser.roles.includes(
                'ADMIN',
            )

        if (
            addingAdmin
            && !adminElevationConfirmed
        ) {
            setModalError(
                'Подтвердите повышение привилегий до ADMIN.',
            )
            return
        }

        await runUserMutation(
            {
                userId: rolesUser.id,
                type: 'ROLES',
            },
            'Не удалось изменить роли пользователя.',
            async () => {
                await updateUserRoles(
                    rolesUser.id,
                    {
                        roles: [selectedRole],
                        expectedVersion,
                    },
                )

                const userEmail = rolesUser.email
                setRolesUser(null)
                setAdminElevationConfirmed(false)
                setSuccess(
                    `Роль пользователя ${userEmail} изменена. `
                    + 'Активные сессии должны быть завершены backend.',
                )
                requestReloadFromFirstPage()
            },
        )
    }

    async function submitResetPassword() {
        if (!resetPasswordUser) {
            return
        }

        const expectedVersion =
            requireVersion(resetPasswordUser)

        if (expectedVersion === null) {
            return
        }

        const passwordError =
            validatePassword(resetPasswordValue)

        if (passwordError) {
            setModalError(passwordError)
            return
        }

        if (
            resetPasswordValue
            !== resetPasswordConfirm
        ) {
            setModalError('Пароли не совпадают.')
            return
        }

        await runUserMutation(
            {
                userId: resetPasswordUser.id,
                type: 'RESET_PASSWORD',
            },
            'Не удалось установить новый пароль.',
            async () => {
                await resetUserPassword(
                    resetPasswordUser.id,
                    {
                        password:
                            resetPasswordValue,
                        expectedVersion,
                    },
                )

                const emailValue =
                    resetPasswordUser.email

                clearMutationSensitiveFields()
                setResetPasswordUser(null)
                setSuccess(
                    `Для ${emailValue} установлен новый пароль. `
                    + 'Активные сессии должны быть завершены backend.',
                )
                requestReloadFromFirstPage()
            },
        )
    }

    function canManageUser(
        user: User,
    ): boolean {
        const targetIsAdmin =
            user.roles.includes('ADMIN')

        return !user.roles.includes(
            'SUPER_ADMIN',
        )
            && user.id !== currentUser.id
            && (
                !targetIsAdmin
                || currentUserIsSuperAdmin
            )
    }

    function canPermanentlyDelete(
        user: User,
    ): boolean {
        if (
            !currentUserIsSuperAdmin
            || !canManageUser(user)
            || user.enabled
            || user.version === null
        ) {
            return false
        }

        const organization = organizations.find(
            (item) =>
                item.id === user.organizationId,
        )

        return Boolean(
            organization
            && !isProtectedOrganization({
                type: organization.type,
                protected:
                    organization.protected,
            }),
        )
    }

    async function submitPermanentDelete() {
        if (!deleteUser) {
            return
        }

        if (!canPermanentlyDelete(deleteUser)) {
            setModalError(
                'Удаление недоступно. '
                + 'Сначала отключите пользователя '
                + 'и убедитесь, что его организация не защищена.',
            )
            return
        }

        const expectedVersion =
            requireVersion(deleteUser)

        if (expectedVersion === null) {
            return
        }

        const canonicalConfirmation =
            normalizeEmail(
                deleteConfirmationEmail,
            )

        if (
            canonicalConfirmation
            !== normalizeEmail(deleteUser.email)
        ) {
            setModalError(
                'Введите email пользователя полностью и без ошибок.',
            )
            return
        }

        await runUserMutation(
            {
                userId: deleteUser.id,
                type: 'DELETE',
            },
            'Не удалось удалить пользователя.',
            async () => {
                await permanentlyDeleteUser(
                    deleteUser.id,
                    {
                        confirmationEmail:
                            canonicalConfirmation,
                        expectedVersion,
                    },
                )

                const deletedEmail =
                    deleteUser.email

                clearMutationSensitiveFields()
                setDeleteUser(null)
                setSuccess(
                    `Пользователь ${deletedEmail} удалён навсегда.`,
                )
                requestReloadFromFirstPage()
            },
        )
    }

    async function confirmEnabledChange() {
        if (!confirmState) {
            return
        }

        const expectedVersion =
            requireVersion(confirmState.user)

        if (expectedVersion === null) {
            return
        }

        const target = confirmState.user
        const nextEnabled =
            confirmState.nextEnabled

        await runUserMutation(
            {
                userId: target.id,
                type: 'ENABLED',
            },
            'Не удалось изменить статус пользователя.',
            async () => {
                await updateUserEnabled(
                    target.id,
                    {
                        enabled: nextEnabled,
                        expectedVersion,
                    },
                )

                setConfirmState(null)
                setSuccess(
                    nextEnabled
                        ? `Пользователь ${target.email} включён.`
                        : (
                            `Пользователь ${target.email} отключён. `
                            + 'Запущен отзыв активных сессий.'
                        ),
                )
                requestReloadFromFirstPage()
            },
            false,
        )
    }

    return {
        mutationError,
        modalError,
        success,
        hasPendingMutation,
        editUser,
        editEmail,
        editFullName,
        rolesUser,
        selectedRole,
        adminElevationConfirmed,
        resetPasswordUser,
        resetPasswordValue,
        resetPasswordConfirm,
        confirmState,
        deleteUser,
        deleteConfirmationEmail,
        setEditEmail,
        setEditFullName,
        setSelectedRole,
        setAdminElevationConfirmed,
        setResetPasswordValue,
        setResetPasswordConfirm,
        setDeleteConfirmationEmail,
        setConfirmState,
        openEditModal,
        openRolesModal,
        openResetPasswordModal,
        openDeleteModal,
        closeMutationModals,
        submitEditUser,
        submitRoles,
        submitResetPassword,
        submitPermanentDelete,
        confirmEnabledChange,
        canManageUser,
        canPermanentlyDelete,
    }
}

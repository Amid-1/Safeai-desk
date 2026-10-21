// frontend/src/features/admin-users/lib/useAdminUserCreate.ts
import {
    useEffect,
    useRef,
    useState,
} from 'react'
import {
    createUser,
} from '../../../entities/user/api/userApi'
import type {
    AuthUser,
} from '../../auth/api/authApi'
import type {
    OrganizationDirectoryItem,
} from '../../../entities/organization/api/organizationApi'
import {
    getApiErrorMessage,
} from '../../../shared/api/http'
import {
    validatePassword,
} from '../../../shared/lib/password'
import {
    useAutoClearMessage,
} from '../../../shared/lib/hooks/useAutoClearMessage'
import {
    resolveManagedUserRole,
} from '../../../entities/user/domain/userManagementRolePolicy'
import {
    normalizeEmail,
    normalizeOptionalText,
} from './adminUsersSupport'
import type {
    AssignableRole,
} from './adminUsersSupport'

const SUCCESS_MESSAGE_TIMEOUT_MS = 4_000

type UseAdminUserCreateOptions = {
    currentUser: AuthUser
    currentUserIsSuperAdmin: boolean
    organizations: OrganizationDirectoryItem[]
    organizationsError: string
    requestReloadFromFirstPage: () => void
}

export function useAdminUserCreate({
    currentUser,
    currentUserIsSuperAdmin,
    organizations,
    organizationsError,
    requestReloadFromFirstPage,
}: UseAdminUserCreateOptions) {
    const [createError, setCreateError] =
        useState('')
    const [success, setSuccess] =
        useState('')
    const [creating, setCreating] =
        useState(false)
    const [createModalOpen, setCreateModalOpen] =
        useState(false)

    const [email, setEmail] = useState('')
    const [password, setPassword] =
        useState('')
    const [passwordConfirm, setPasswordConfirm] =
        useState('')
    const [fullName, setFullName] =
        useState('')
    const [createRole, setCreateRole] =
        useState<AssignableRole>('USER')
    const [
        selectedOrganizationId,
        setSelectedOrganizationId,
    ] = useState('')

    const creatingRef = useRef(false)

    useEffect(() => {
        if (!currentUserIsSuperAdmin) {
            setSelectedOrganizationId('')
            return
        }

        setSelectedOrganizationId(
            (current) =>
                organizations.some(
                    (organization) =>
                        organization.id === current,
                )
                    ? current
                    : '',
        )
    }, [
        currentUserIsSuperAdmin,
        organizations,
    ])

    useAutoClearMessage(
        success,
        setSuccess,
        SUCCESS_MESSAGE_TIMEOUT_MS,
    )

    function clearSensitiveFields() {
        setPassword('')
        setPasswordConfirm('')
    }

    function openCreateModal() {
        clearSensitiveFields()
        setEmail('')
        setFullName('')
        setCreateRole('USER')
        setSelectedOrganizationId('')
        setCreateError('')
        setCreateModalOpen(true)
    }

    function closeCreateModal() {
        if (creatingRef.current) {
            return
        }

        clearSensitiveFields()
        setCreateModalOpen(false)
        setCreateError('')
    }

    async function handleCreateUser() {
        if (creatingRef.current) {
            return
        }

        setCreateError('')
        setSuccess('')

        const normalizedEmail =
            normalizeEmail(email)
        const passwordError =
            validatePassword(password)

        const targetOrganizationId =
            currentUserIsSuperAdmin
                ? selectedOrganizationId
                : currentUser.organizationId

        if (!normalizedEmail) {
            setCreateError(
                'Введите email пользователя.',
            )
            return
        }

        if (passwordError) {
            setCreateError(passwordError)
            return
        }

        if (password !== passwordConfirm) {
            setCreateError('Пароли не совпадают.')
            return
        }

        if (!targetOrganizationId) {
            setCreateError('Выберите организацию.')
            return
        }

        const requestedRole =
            resolveManagedUserRole(
                currentUser.roles,
                createRole,
            )

        creatingRef.current = true
        setCreating(true)

        try {
            const created = await createUser({
                organizationId:
                    targetOrganizationId,
                email: normalizedEmail,
                password,
                fullName:
                    normalizeOptionalText(
                        fullName,
                    ),
                roles: [requestedRole],
            })

            clearSensitiveFields()
            setEmail('')
            setFullName('')
            setCreateRole('USER')
            setSelectedOrganizationId('')
            setCreateModalOpen(false)
            setSuccess(
                `Пользователь ${created.email} создан.`,
            )
            requestReloadFromFirstPage()
        } catch (error) {
            setCreateError(
                getApiErrorMessage(
                    error,
                    'Не удалось создать пользователя.',
                ),
            )
        } finally {
            creatingRef.current = false
            setCreating(false)
        }
    }

    return {
        createError:
            createError || organizationsError,
        success,
        creating,
        createModalOpen,
        email,
        password,
        passwordConfirm,
        fullName,
        createRole,
        selectedOrganizationId,
        setEmail,
        setPassword,
        setPasswordConfirm,
        setFullName,
        setCreateRole,
        setSelectedOrganizationId,
        openCreateModal,
        closeCreateModal,
        handleCreateUser,
    }
}

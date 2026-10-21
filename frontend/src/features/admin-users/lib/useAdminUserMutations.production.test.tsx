// ============================================================
// frontend/src/features/admin-users/lib/useAdminUserMutations.production.test.tsx
// Проверки прав, версий и конкурентных операций с пользователями.
// ============================================================
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../../auth/api/authApi'
import type { User, UserDetails } from '../../../entities/user/api/userApi'
import type { OrganizationDirectoryItem } from '../../../entities/organization/api/organizationApi'
import {
    getUserDetails,
    permanentlyDeleteUser,
    resetUserPassword,
    updateUser,
    updateUserEnabled,
    updateUserRoles,
} from '../../../entities/user/api/userApi'
import { useAdminUserMutations } from './useAdminUserMutations'
import { ApiError } from '../../../shared/api/http'

vi.mock('../../../entities/user/api/userApi', () => ({
    getUserDetails: vi.fn(),
    permanentlyDeleteUser: vi.fn(),
    resetUserPassword: vi.fn(),
    updateUser: vi.fn(),
    updateUserEnabled: vi.fn(),
    updateUserRoles: vi.fn(),
}))

const ADMIN_ID = '11111111-1111-4111-8111-111111111111'
const USER_ID = '22222222-2222-4222-8222-222222222222'
const TENANT_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const OTHER_TENANT = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

const admin: AuthUser = {
    id: ADMIN_ID,
    organizationId: TENANT_ID,
    email: 'admin@safeai.test',
    fullName: null,
    enabled: true,
    roles: ['ADMIN'],
}

const tenant: OrganizationDirectoryItem = {
    id: TENANT_ID,
    name: 'Арендатор',
    type: 'TENANT',
    protected: false,
    enabled: true,
    version: 1,
}

function user(overrides: Partial<User> = {}): User {
    return {
        id: USER_ID,
        organizationId: TENANT_ID,
        email: 'member@safeai.test',
        fullName: 'Сотрудник',
        enabled: true,
        roles: ['USER'],
        version: 7,
        createdAt: '2026-10-20T12:00:00Z',
        updatedAt: '2026-10-20T12:00:00Z',
        lastLoginAt: null,
        ...overrides,
    }
}

/**
 * Ответ подробного API имеет обязательное поле organizationName.
 * Фикстура User используется только там, где API действительно возвращает User.
 * Тестовые данные не обходят контракт через приведение типов.
 */
function userDetails(overrides: Partial<UserDetails> = {}): UserDetails {
    return {
        ...user(),
        organizationName: 'Арендатор',
        ...overrides,
    }
}

const update = vi.mocked(updateUser)
const roles = vi.mocked(updateUserRoles)
const reset = vi.mocked(resetUserPassword)
const erase = vi.mocked(permanentlyDeleteUser)
const toggle = vi.mocked(updateUserEnabled)
const details = vi.mocked(getUserDetails)

function mount(options: {
    superAdmin?: boolean
    currentUser?: AuthUser
    organizations?: OrganizationDirectoryItem[]
} = {}) {
    const reload = vi.fn()
    const { result, unmount } = renderHook(() => useAdminUserMutations({
        currentUser: options.currentUser ?? admin,
        currentUserIsSuperAdmin: options.superAdmin ?? false,
        organizations: options.organizations ?? [tenant],
        requestReloadFromFirstPage: reload,
    }))
    return { result, reload, unmount }
}

beforeEach(() => {
    vi.clearAllMocks()
    update.mockResolvedValue(user({ version: 8 }))
    roles.mockResolvedValue(user({ version: 8 }))
    reset.mockResolvedValue(undefined)
    erase.mockResolvedValue(undefined)
    toggle.mockResolvedValue(user({ version: 8 }))
    details.mockResolvedValue(userDetails({ version: 8 }))
})

describe('useAdminUserMutations: границы прав', () => {
    it('не управляет чужой организацией через ADMIN, включая прямой вызов обработчика', async () => {
        const { result } = mount()
        const target = user({ organizationId: OTHER_TENANT })
        expect(result.current.canManageUser(target)).toBe(false)
        act(() => result.current.openEditModal(target))
        expect(result.current.editUser).toBeNull()
        expect(result.current.mutationError).toContain('Недостаточно прав')
        act(() => result.current.setConfirmState({ user: target, nextEnabled: false }))
        await act(async () => result.current.confirmEnabledChange())
        expect(toggle).not.toHaveBeenCalled()
    })

    it('запрещает модификацию собственной записи и SUPER_ADMIN', () => {
        const { result } = mount({ superAdmin: true })
        expect(result.current.canManageUser(user({ id: ADMIN_ID }))).toBe(false)
        expect(result.current.canManageUser(user({ roles: ['SUPER_ADMIN'] }))).toBe(false)
        act(() => result.current.openResetPasswordModal(user({ id: ADMIN_ID })))
        expect(result.current.resetPasswordUser).toBeNull()
    })

    it('не даёт ADMIN редактировать роли, а SUPER_ADMIN требует подтверждение повышения', async () => {
        const adminView = mount()
        act(() => adminView.result.current.openRolesModal(user()))
        expect(adminView.result.current.rolesUser).toBeNull()
        adminView.unmount()

        const superView = mount({ superAdmin: true })
        act(() => superView.result.current.openRolesModal(user()))
        act(() => superView.result.current.setSelectedRole('ADMIN'))
        await act(async () => superView.result.current.submitRoles())
        expect(roles).not.toHaveBeenCalled()
        expect(superView.result.current.modalError).toContain('Подтвердите повышение')
        act(() => superView.result.current.setAdminElevationConfirmed(true))
        await act(async () => superView.result.current.submitRoles())
        expect(roles).toHaveBeenCalledWith(USER_ID, {
            roles: ['ADMIN'], expectedVersion: 7,
        })
        expect(superView.reload).toHaveBeenCalledTimes(1)
    })

    it('отклоняет недопустимую роль даже при обходе типов TypeScript', async () => {
        const { result } = mount({ superAdmin: true })
        act(() => result.current.openRolesModal(user()))
        act(() => result.current.setSelectedRole('SUPER_ADMIN' as 'ADMIN'))
        await act(async () => result.current.submitRoles())
        expect(roles).not.toHaveBeenCalled()
        expect(result.current.modalError).toContain('Недопустимая системная роль')
    })

    it('невозможно назначить роль защищённого пользователя даже при прямом вызове', async () => {
        const { result } = mount({ superAdmin: true })
        act(() => result.current.openRolesModal(user({ roles: ['SUPER_ADMIN'] })))
        expect(result.current.rolesUser).toBeNull()
        await act(async () => result.current.submitRoles())
        expect(roles).not.toHaveBeenCalled()
    })
})

describe('useAdminUserMutations: версии, редактирование и конкуренция', () => {
    it.each([null, -1, Number.NaN, 1.5, Number.MAX_SAFE_INTEGER + 1])(
        'блокирует некорректный optimistic version: %s', (version) => {
            const { result } = mount()
            act(() => result.current.openEditModal(user({
                version: version as number,
            })))
            expect(result.current.editUser).toBeNull()
            expect(result.current.mutationError).toContain('version')
        },
    )

    it('отправляет normalized email, имя и исходную версию', async () => {
        const { result, reload } = mount()
        act(() => result.current.openEditModal(user()))
        act(() => result.current.setEditEmail('  NEW@EXAMPLE.COM  '))
        act(() => result.current.setEditFullName('  Иван    Иванов  '))
        await act(async () => result.current.submitEditUser())
        expect(update).toHaveBeenCalledWith(USER_ID, {
            email: 'new@example.com',
            fullName: 'Иван Иванов',
            expectedVersion: 7,
        })
        expect(result.current.editUser).toBeNull()
        expect(reload).toHaveBeenCalledTimes(1)
    })

    it('блокирует сохранение после конфликта версии до получения свежей записи', async () => {
        const { result, reload } = mount()
        act(() => result.current.openEditModal(user()))
        update.mockRejectedValueOnce(new Error('not a version conflict'))
        await act(async () => result.current.submitEditUser())
        expect(result.current.editUser).not.toBeNull()
        expect(reload).not.toHaveBeenCalled()
    })

    it('принимает корректные UserDetails с null organizationName и новой версией', async () => {
        const { result } = mount()
        act(() => result.current.openEditModal(user()))
        details.mockResolvedValueOnce(userDetails({
            organizationName: null,
            version: 8,
        }))

        await act(async () => result.current.refreshEditedUser())

        expect(result.current.editUser?.version).toBe(8)
        expect(result.current.editUser).toHaveProperty('organizationName', null)
        expect(result.current.editVersionConflict).toBe(false)
        expect(details).toHaveBeenCalledWith(USER_ID)
    })

    it('не заменяет пользователя ответом другой организации при refresh', async () => {
        const { result } = mount()
        act(() => result.current.openEditModal(user()))
        details.mockResolvedValueOnce(userDetails({
            organizationId: OTHER_TENANT,
            organizationName: 'Другая организация',
            version: 8,
        }))
        await act(async () => result.current.refreshEditedUser())
        expect(result.current.editVersionConflict).toBe(true)
        expect(result.current.editUser?.organizationId).toBe(TENANT_ID)
        expect(result.current.modalError).toContain('другого пользователя')
    })

    it('при конфликте 409 блокирует повторное редактирование и запрашивает свежий список', async () => {
        const { result, reload } = mount()
        act(() => result.current.openEditModal(user()))
        update.mockRejectedValueOnce(new ApiError(
            'Конкурентное изменение',
            { code: 'USER_VERSION_CONFLICT' },
            409,
        ))
        await act(async () => result.current.submitEditUser())
        expect(result.current.editVersionConflict).toBe(true)
        expect(result.current.editUser).not.toBeNull()
        expect(reload).toHaveBeenCalledTimes(1)
        await act(async () => result.current.submitEditUser())
        expect(update).toHaveBeenCalledTimes(1)
    })

    it('успешный POST не считается неудачным, если упало обновление UI', async () => {
        const onReload = vi.fn(() => { throw new Error('Ошибка UI') })
        const { result } = renderHook(() => useAdminUserMutations({
            currentUser: admin,
            currentUserIsSuperAdmin: false,
            organizations: [tenant],
            requestReloadFromFirstPage: onReload,
        }))
        act(() => result.current.openEditModal(user()))
        await act(async () => result.current.submitEditUser())
        expect(update).toHaveBeenCalledTimes(1)
        expect(result.current.success).toContain('обновлён')
        expect(result.current.mutationError).toContain('не удалось обновить список')
    })

    it('не закрывает текущую форму во время незавершённой мутации', async () => {
        let finish!: (value: User) => void
        update.mockImplementationOnce(() => new Promise<User>((resolve) => { finish = resolve }))
        const { result } = mount()
        act(() => result.current.openEditModal(user()))
        await act(async () => {
            const pending = result.current.submitEditUser()
            result.current.closeMutationModals()
            expect(result.current.editUser).not.toBeNull()
            finish(user({ version: 8 }))
            await pending
        })
    })

    it('не подменяет ID и не принимает запись с некорректной версией', async () => {
        const { result } = mount()
        act(() => result.current.openEditModal(user()))
        details.mockResolvedValueOnce(userDetails({ id: ADMIN_ID }))
        await act(async () => result.current.refreshEditedUser())
        expect(result.current.editVersionConflict).toBe(true)
        expect(result.current.editUser?.id).toBe(USER_ID)
    })

    it('при повторном быстром submit делает ровно один запрос', async () => {
        let finish!: (value: User) => void
        update.mockImplementationOnce(() => new Promise<User>((resolve) => { finish = resolve }))
        const { result } = mount()
        act(() => result.current.openEditModal(user()))
        await act(async () => {
            const first = result.current.submitEditUser()
            const second = result.current.submitEditUser()
            expect(update).toHaveBeenCalledTimes(1)
            finish(user({ version: 8 }))
            await Promise.all([first, second])
        })
        expect(update).toHaveBeenCalledTimes(1)
    })

    it('во время редактирования не открывает другой модальный сценарий', async () => {
        let finish!: (value: User) => void
        update.mockImplementationOnce(() => new Promise<User>((resolve) => { finish = resolve }))
        const { result } = mount()
        act(() => result.current.openEditModal(user()))
        await act(async () => {
            const pending = result.current.submitEditUser()
            result.current.openResetPasswordModal(user())
            expect(result.current.resetPasswordUser).toBeNull()
            finish(user({ version: 8 }))
            await pending
        })
    })
})

describe('useAdminUserMutations: пароль, статус и необратимое удаление', () => {
    it('проверяет пароль до вызова API и совпадение подтверждения', async () => {
        const { result } = mount()
        act(() => result.current.openResetPasswordModal(user()))
        await act(async () => result.current.submitResetPassword())
        expect(reset).not.toHaveBeenCalled()
        act(() => result.current.setResetPasswordValue('LongSafePassword_2026!'))
        act(() => result.current.setResetPasswordConfirm('OTHER'))
        await act(async () => result.current.submitResetPassword())
        expect(result.current.modalError).toBe('Пароли не совпадают.')
        expect(reset).not.toHaveBeenCalled()
        act(() => result.current.setResetPasswordConfirm('LongSafePassword_2026!'))
        await act(async () => result.current.submitResetPassword())
        expect(reset).toHaveBeenCalledWith(USER_ID, {
            password: 'LongSafePassword_2026!', expectedVersion: 7,
        })
        expect(result.current.resetPasswordValue).toBe('')
        expect(result.current.resetPasswordConfirm).toBe('')
    })

    it('запрещает permanent delete без SUPER_ADMIN, деактивации и реестра организации', () => {
        const disabled = user({ enabled: false })
        expect(mount().result.current.canPermanentlyDelete(disabled)).toBe(false)
        expect(mount({ superAdmin: true }).result.current.canPermanentlyDelete(user())).toBe(false)
        expect(mount({ superAdmin: true, organizations: [] }).result.current.canPermanentlyDelete(disabled)).toBe(false)
        expect(mount({ superAdmin: true, organizations: [{ ...tenant, protected: true }] }).result.current.canPermanentlyDelete(disabled)).toBe(false)
        expect(mount({ superAdmin: true }).result.current.canPermanentlyDelete(disabled)).toBe(true)
    })

    it('требует точный email перед постоянным удалением и передаёт expectedVersion', async () => {
        const { result, reload } = mount({ superAdmin: true })
        act(() => result.current.openDeleteModal(user({ enabled: false })))
        act(() => result.current.setDeleteConfirmationEmail('wrong@example.com'))
        await act(async () => result.current.submitPermanentDelete())
        expect(erase).not.toHaveBeenCalled()
        act(() => result.current.setDeleteConfirmationEmail(' MEMBER@SAFEAI.TEST '))
        await act(async () => result.current.submitPermanentDelete())
        expect(erase).toHaveBeenCalledWith(USER_ID, {
            confirmationEmail: 'member@safeai.test', expectedVersion: 7,
        })
        expect(result.current.deleteConfirmationEmail).toBe('')
        expect(reload).toHaveBeenCalledTimes(1)
    })

    it('изменяет enabled только с исходной версией и не отправляет повторный POST', async () => {
        const { result, reload } = mount()
        act(() => result.current.setConfirmState({ user: user(), nextEnabled: false }))
        await act(async () => result.current.confirmEnabledChange())
        expect(toggle).toHaveBeenCalledWith(USER_ID, { enabled: false, expectedVersion: 7 })
        expect(result.current.confirmState).toBeNull()
        expect(reload).toHaveBeenCalledTimes(1)
    })

    it('блокирует подтверждение состояния для SUPER_ADMIN даже при ручном setConfirmState', async () => {
        const { result } = mount()
        act(() => result.current.setConfirmState({
            user: user({ roles: ['SUPER_ADMIN'] }), nextEnabled: false,
        }))
        await act(async () => result.current.confirmEnabledChange())
        expect(toggle).not.toHaveBeenCalled()
    })
})

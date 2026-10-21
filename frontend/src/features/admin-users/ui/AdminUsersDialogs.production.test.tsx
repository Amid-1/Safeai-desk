// frontend/src/features/admin-users/ui/AdminUsersDialogs.production.test.tsx
// ============================================================
// frontend/src/features/admin-users/ui/AdminUsersDialogs.production.test.tsx
// ============================================================
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { User, UserDetails } from '../../../entities/user/api/userApi'
import type { AdminUsersDialogsProps } from './AdminUsersDialogs'
import { AdminUsersDialogs } from './AdminUsersDialogs'

const ORG_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const USER_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const USER: User = {
    id: USER_ID,
    organizationId: ORG_ID,
    email: 'user@company.ru',
    fullName: 'Тестовый Пользователь',
    enabled: false,
    roles: ['USER'],
    version: 7,
    createdAt: '2026-10-01T10:00:00Z',
    updatedAt: '2026-10-02T10:00:00Z',
    lastLoginAt: null,
}
const DETAILS: UserDetails = { ...USER, organizationName: 'Тестовая компания' }

function createProps(
    overrides: Partial<AdminUsersDialogsProps> = {},
): AdminUsersDialogsProps {
    return {
        createModalOpen: false,
        createError: '',
        email: 'new@company.ru',
        password: 'ComplexPassword!123',
        passwordConfirm: 'ComplexPassword!123',
        fullName: 'Новый пользователь',
        createRole: 'USER',
        currentUserIsSuperAdmin: false,
        organizationsLoading: false,
        organizations: [{
            id: ORG_ID,
            name: 'Тестовая компания',
            type: 'TENANT',
            enabled: true,
            protected: false,
            version: 1,
        }],
        selectedOrganizationId: ORG_ID,
        creating: false,
        detailsUser: null,
        detailsError: '',
        editUser: null,
        editVersionConflict: false,
        refreshEditedUser: vi.fn(),
        editEmail: USER.email,
        editFullName: USER.fullName ?? '',
        rolesUser: null,
        selectedRole: 'USER',
        adminElevationConfirmed: false,
        resetPasswordUser: null,
        resetPasswordValue: 'ComplexPassword!123',
        resetPasswordConfirm: 'ComplexPassword!123',
        deleteUser: null,
        deleteConfirmationEmail: '',
        modalError: '',
        hasPendingMutation: false,
        confirmState: null,
        setEmail: vi.fn(),
        setPassword: vi.fn(),
        setPasswordConfirm: vi.fn(),
        setFullName: vi.fn(),
        setCreateRole: vi.fn(),
        setSelectedOrganizationId: vi.fn(),
        setEditEmail: vi.fn(),
        setEditFullName: vi.fn(),
        setSelectedRole: vi.fn(),
        setAdminElevationConfirmed: vi.fn(),
        setResetPasswordValue: vi.fn(),
        setResetPasswordConfirm: vi.fn(),
        setDeleteConfirmationEmail: vi.fn(),
        setConfirmState: vi.fn(),
        handleCreateUser: vi.fn(),
        closeCreateModal: vi.fn(),
        closeDetailsModal: vi.fn(),
        closeMutationModals: vi.fn(),
        submitEditUser: vi.fn(),
        submitRoles: vi.fn(),
        submitResetPassword: vi.fn(),
        submitPermanentDelete: vi.fn(),
        confirmEnabledChange: vi.fn(),
        ...overrides,
    }
}

function open(overrides: Partial<AdminUsersDialogsProps>) {
    const props = createProps(overrides)
    const view = render(<AdminUsersDialogs {...props} />)
    return { props, ...view }
}

function submitDialogForm() {
    const form = screen.getByRole('dialog').querySelector('form')
    if (!form) throw new Error('Форма диалога отсутствует')
    fireEvent.submit(form)
}

describe('AdminUsersDialogs: сохранение контрактов и защитных ограничений', () => {
    it('не показывает окна, если нет активной операции', () => {
        open({})
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('обычный администратор создаёт только USER без выбора организации или роли', () => {
        const { props } = open({ createModalOpen: true })
        expect(screen.getByRole('heading', { name: 'Создать пользователя' })).toBeInTheDocument()
        expect(screen.getByText('Пользователь будет создан в вашей организации.')).toBeInTheDocument()
        expect(screen.queryByRole('radio')).not.toBeInTheDocument()
        expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
        submitDialogForm()
        expect(props.handleCreateUser).toHaveBeenCalledTimes(1)
        fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
        expect(props.closeCreateModal).toHaveBeenCalledTimes(1)
    })

    it('SUPER_ADMIN выбирает tenant, роль и видит предупреждение о полномочиях ADMIN', () => {
        const { props, rerender } = open({ createModalOpen: true, currentUserIsSuperAdmin: true })
        const select = screen.getByRole('combobox')
        fireEvent.change(select, { target: { value: ORG_ID } })
        expect(props.setSelectedOrganizationId).toHaveBeenCalledWith(ORG_ID)
        fireEvent.click(screen.getByRole('radio', { name: /Администратор/ }))
        expect(props.setCreateRole).toHaveBeenCalledWith('ADMIN')
        rerender(<AdminUsersDialogs {...props} createRole="ADMIN" />)
        expect(screen.getByText(/не сможет создавать/)).toBeInTheDocument()
    })

    it('создание не закрывается и не отправляется повторно во время операции', () => {
        const { props } = open({ createModalOpen: true, creating: true, createError: 'Ошибка' })
        expect(screen.getByRole('button', { name: 'Создание...' })).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Отмена' })).toBeDisabled()
        expect(screen.getByRole('textbox', { name: 'Email' })).toBeDisabled()
        expect(screen.getByRole('alert')).toHaveTextContent('Ошибка')
        expect(props.handleCreateUser).not.toHaveBeenCalled()
    })

    it('сведения пользователя скрывают UUID от обычного ADMIN', () => {
        const { props } = open({ detailsUser: DETAILS })
        expect(screen.getByText('Тестовая компания')).toBeInTheDocument()
        expect(screen.queryByText('UUID пользователя')).not.toBeInTheDocument()
        expect(screen.queryByText('UUID организации')).not.toBeInTheDocument()
        expect(screen.getByText(USER.email)).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Закрыть окно' }))
        expect(props.closeDetailsModal).toHaveBeenCalledTimes(1)
    })

    it('SUPER_ADMIN видит UUID пользователя и организации в сведениях', () => {
        open({ detailsUser: DETAILS, currentUserIsSuperAdmin: true })
        expect(screen.getByText('UUID пользователя')).toBeInTheDocument()
        expect(screen.getByText('UUID организации')).toBeInTheDocument()
    })

    it('редактирование блокирует пустое изменение и сообщает об отзыве сессий при смене логина', () => {
        const { props, rerender } = open({ editUser: USER })
        expect(screen.getByRole('button', { name: 'Сохранить изменения' })).toBeDisabled()
        rerender(<AdminUsersDialogs {...props} editEmail="changed@company.ru" />)
        expect(screen.getByText(/Изменение email изменит логин/)).toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Сохранить изменения' })).toBeEnabled()
        submitDialogForm()
        expect(props.submitEditUser).toHaveBeenCalledTimes(1)
    })

    it('конфликт версии не допускает сохранения до повторной загрузки', () => {
        const { props } = open({ editUser: USER, editEmail: 'changed@company.ru', editVersionConflict: true })
        expect(screen.getByRole('button', { name: 'Сохранить изменения' })).toBeDisabled()
        expect(screen.getByRole('alert')).toHaveTextContent('Запись была изменена')
        fireEvent.click(screen.getByRole('button', { name: 'Загрузить актуальные данные' }))
        expect(props.refreshEditedUser).toHaveBeenCalledTimes(1)
    })

    it('изменение роли недоступно обычному ADMIN', () => {
        open({ rolesUser: USER, selectedRole: 'ADMIN' })
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('повышение до ADMIN требует отдельного подтверждения и сбрасывает его при выборе роли', () => {
        const { props, rerender } = open({
            rolesUser: USER,
            currentUserIsSuperAdmin: true,
            selectedRole: 'ADMIN',
        })
        expect(screen.getByRole('button', { name: 'Сохранить роль' })).toBeDisabled()
        expect(screen.getByText(/Подтверждаю повышение/)).toBeInTheDocument()
        fireEvent.click(screen.getByRole('radio', { name: /Пользователь/ }))
        expect(props.setSelectedRole).toHaveBeenCalledWith('USER')
        expect(props.setAdminElevationConfirmed).toHaveBeenCalledWith(false)
        rerender(<AdminUsersDialogs {...props} adminElevationConfirmed />)
        expect(screen.getByRole('button', { name: 'Сохранить роль' })).toBeEnabled()
        submitDialogForm()
        expect(props.submitRoles).toHaveBeenCalledTimes(1)
    })

    it('смена пароля блокирует несовпадение и более 72 байт UTF-8', () => {
        const { props, rerender } = open({
            resetPasswordUser: USER,
            resetPasswordConfirm: 'DifferentPassword!123',
        })
        expect(screen.getByRole('button', { name: 'Установить новый пароль' })).toBeDisabled()
        rerender(<AdminUsersDialogs {...props} resetPasswordValue={'Ж'.repeat(37)} resetPasswordConfirm={'Ж'.repeat(37)} />)
        expect(screen.getByRole('button', { name: 'Установить новый пароль' })).toBeDisabled()
        expect(screen.getByText(/превышен допустимый предел/)).toBeInTheDocument()
        rerender(<AdminUsersDialogs {...props} />)
        expect(screen.getByRole('button', { name: 'Установить новый пароль' })).toBeEnabled()
        submitDialogForm()
        expect(props.submitResetPassword).toHaveBeenCalledTimes(1)
    })

    it('без точного подтверждения email постоянное удаление заблокировано', () => {
        const { props, rerender } = open({ deleteUser: USER })
        expect(screen.getByRole('button', { name: 'Удалить навсегда' })).toBeDisabled()
        fireEvent.change(screen.getByRole('textbox', { name: 'Введите email пользователя' }), {
            target: { value: 'user@company.ru' },
        })
        expect(props.setDeleteConfirmationEmail).toHaveBeenCalledWith('user@company.ru')
        rerender(<AdminUsersDialogs {...props} deleteConfirmationEmail=" USER@COMPANY.RU " />)
        expect(screen.getByRole('button', { name: 'Удалить навсегда' })).toBeEnabled()
        submitDialogForm()
        expect(props.submitPermanentDelete).toHaveBeenCalledTimes(1)
    })

    it('подтверждение отключения предупреждает об отзыве сессий', () => {
        const { props } = open({ confirmState: { user: USER, nextEnabled: false } })
        const dialog = screen.getByRole('dialog')
        expect(within(dialog).getByText(/активные сессии будут отозваны/)).toBeInTheDocument()
        expect(within(dialog).getByRole('button', { name: 'Отключить пользователя' })).toBeInTheDocument()
        fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
        expect(props.setConfirmState).toHaveBeenCalledWith(null)
    })

    it('SUPER_ADMIN не может завершить создание до загрузки организаций', () => {
        open({
            createModalOpen: true,
            currentUserIsSuperAdmin: true,
            organizationsLoading: true,
        })
        expect(screen.getByRole('combobox')).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Создать пользователя' })).toBeDisabled()
        expect(screen.getByText('Загрузка организаций...')).toBeInTheDocument()
    })

    it('при любой активной мутации опасные диалоги блокируют отмену и сохранение', () => {
        open({
            deleteUser: USER,
            deleteConfirmationEmail: USER.email,
            hasPendingMutation: true,
        })
        expect(screen.getByRole('button', { name: 'Удалить навсегда' })).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Отмена' })).toBeDisabled()
    })

    it('при активной мутации подтверждение отключения недоступно', () => {
        const { props } = open({
            confirmState: { user: USER, nextEnabled: true },
            hasPendingMutation: true,
        })
        expect(screen.getByRole('button', { name: 'Выполняется...' })).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Отмена' })).toBeDisabled()
        expect(props.confirmEnabledChange).not.toHaveBeenCalled()
    })
})

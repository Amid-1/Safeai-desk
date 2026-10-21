// frontend/src/components/admin/users/adminUsersView/UserRoleInfoDialog.tsx
import type { AuthUser } from '../../../../api/authApi'
import type { User } from '../../../../api/userApi'
import Modal from '../../../Modal'
import UserRoleBadge from '../../UserRoleBadge'

type Props = {
    roleInfoUser: User | null
    currentUser: AuthUser
    currentUserIsSuperAdmin: boolean
    onClose: () => void
}
export function UserRoleInfoDialog({ roleInfoUser, currentUser, currentUserIsSuperAdmin, onClose }: Props) {
    const roleInfoIsSelf = roleInfoUser?.id === currentUser.id
    const roleInfoIsProtected = roleInfoUser?.roles.includes('SUPER_ADMIN') ?? false
    const setRoleInfoUser = (_user: User | null) => onClose()
    return (
        <>
            {roleInfoUser && (
                <Modal
                    title="Системная роль пользователя"
                    size="sm"
                    onClose={() => setRoleInfoUser(null)}
                >
                    <div className="users-role-info-dialog">
                        <p className="modal-subtitle">
                            {roleInfoUser.email}
                        </p>
                        <div className="users-role-info-dialog__current">
                            <strong>Текущая роль</strong>
                            <UserRoleBadge role={roleInfoUser.roles[0]} />
                        </div>
                        <p role="note" className="users-security-note">
                            {!currentUserIsSuperAdmin
                                ? 'Изменять системные роли пользователей может только SUPER_ADMIN. Обратитесь к администратору платформы для назначения роли ADMIN.'
                                : roleInfoIsSelf
                                    ? 'Собственную системную роль нельзя изменять через управление пользователями.'
                                    : roleInfoIsProtected
                                        ? 'Роль SUPER_ADMIN защищена и не изменяется через обычное управление пользователями.'
                                        : 'Изменение системной роли этой учетной записи сейчас недоступно.'}
                        </p>
                        <p className="muted">
                            Проверка прав выполняется на сервере. Просмотр роли не изменяет учетную запись и не отзывает сессии.
                        </p>
                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                onClick={() => setRoleInfoUser(null)}
                            >
                                Закрыть
                            </button>
                        </div>
                    </div>
                </Modal>
            )}
        </>
    )
}

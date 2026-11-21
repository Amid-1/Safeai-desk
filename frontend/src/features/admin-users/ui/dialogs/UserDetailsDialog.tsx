// frontend/src/features/admin-users/ui/dialogs/UserDetailsDialog.tsx
import type { AdminUsersDialogsProps } from '../AdminUsersDialogs.types'
import Modal from '../../../../shared/ui/Modal'
import { Detail, UserStatusBadge } from '../AdminUsersUi'
import { UserExactDate, UserUuidCopy } from '../UserDetailExtras'
import { findOrganizationName, getRoleLabel } from '../../lib/adminUsersSupport'
import { DETAIL_USER_WINDOW } from '../../../../shared/ui/adminDetailWindow'

type UserDetailsDialogProps = Pick<AdminUsersDialogsProps,
    | 'detailsUser'
    | 'closeDetailsModal'
    | 'detailsError'
    | 'currentUserIsSuperAdmin'
    | 'organizations'
>

function userInitials(fullName: string | null | undefined, email: string): string {
    const name = fullName?.trim() || email.trim()
    const words = name.split(/\s+/).filter(Boolean)
    return (words.length > 1 ? words[0].slice(0, 1) + words[1].slice(0, 1) : name.slice(0, 2)).toUpperCase()
}

export function UserDetailsDialog({
    detailsUser, closeDetailsModal, detailsError, currentUserIsSuperAdmin, organizations,
}: UserDetailsDialogProps) {
    if (!detailsUser) return null
    const role = detailsUser.roles[0]
    const organizationName = detailsUser.organizationName
        ?? findOrganizationName(detailsUser.organizationId, organizations)
        ?? 'Название недоступно'
    const roleTone = role === 'SUPER_ADMIN' ? 'super-admin' : role === 'ADMIN' ? 'admin' : 'user'

    return <Modal title="Карточка пользователя" onClose={closeDetailsModal} size="lg"
        className="admin-details-dialog admin-profile-window" resize={DETAIL_USER_WINDOW}>
        <div className="admin-user-profile">
            {detailsError && <div className="error" role="alert">{detailsError}</div>}
            <header className="admin-profile-hero admin-user-profile__hero">
                <div className={`admin-profile-icon admin-profile-icon--${roleTone}`} aria-hidden="true">
                    {userInitials(detailsUser.fullName, detailsUser.email)}
                </div>
                <div className="admin-profile-hero__identity">
                    <strong>{detailsUser.fullName?.trim() || 'Без имени'}</strong>
                    <span className="admin-user-profile__email">{detailsUser.email}</span>
                    <span className={`admin-profile-role admin-profile-role--${roleTone}`}>{getRoleLabel(role)}</span>
                </div>
                <UserStatusBadge enabled={detailsUser.enabled} />
            </header>
            <section className="admin-profile-section" aria-label="Учётная запись">
                <h3>Учётная запись</h3>
                <dl className="user-details admin-profile-facts">
                    <Detail term="Организация" value={currentUserIsSuperAdmin ?
                        <a className="admin-related-link" href={`/admin/organizations?organizationId=${encodeURIComponent(detailsUser.organizationId)}`}>
                            {organizationName} ↗
                        </a> : organizationName} />
                    <Detail term="Роль" value={detailsUser.roles.map(getRoleLabel).join(', ')} />
                    <Detail term="Статус доступа" value={<UserStatusBadge enabled={detailsUser.enabled} />} />
                </dl>
            </section>
            <section className="admin-profile-section" aria-label="История учётной записи">
                <h3>История</h3>
                <dl className="user-details admin-profile-facts">
                    <Detail term="Дата создания" value={<UserExactDate value={detailsUser.createdAt} />} />
                    <Detail term="Последнее изменение" value={<UserExactDate value={detailsUser.updatedAt} />} />
                    <Detail term="Последний вход" value={<UserExactDate value={detailsUser.lastLoginAt} />} />
                </dl>
            </section>
            {!detailsError && <section className="admin-profile-section" aria-label="Связанные отчёты">
                <h3>Связанные отчёты</h3>
                <div className="admin-related-actions">
                    <a className="admin-related-link" href={`/admin/audit?actorUserId=${encodeURIComponent(detailsUser.id)}`}>Журнал событий</a>
                    <a className="admin-related-link" href={`/admin/usage?userId=${encodeURIComponent(detailsUser.id)}`}>Использование и расходы</a>
                </div>
            </section>}
            {currentUserIsSuperAdmin && <details className="admin-technical-details">
                <summary>Технические сведения</summary>
                <dl className="user-details">
                    <Detail term="Идентификатор пользователя" value={<UserUuidCopy value={detailsUser.id} label="UUID пользователя" />} />
                    <Detail term="Идентификатор организации" value={<UserUuidCopy value={detailsUser.organizationId} label="UUID организации" />} />
                </dl>
            </details>}
            <div className="modal-actions admin-profile-footer">
                <button type="button" className="secondary-button" onClick={closeDetailsModal}>Закрыть</button>
            </div>
        </div>
    </Modal>
}

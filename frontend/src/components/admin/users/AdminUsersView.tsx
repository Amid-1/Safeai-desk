// frontend/src/components/admin/users/AdminUsersView.tsx
import { useState } from 'react'
import type { User } from '../../../api/userApi'
import ResizableScrollRegion from '../../ResizableScrollRegion'
import { Pagination } from './AdminUsersUi'
import type { AdminUsersViewProps } from './adminUsersViewTypes'
import { UsersDirectoryUpper } from './adminUsersView/UsersDirectoryUpper'
import { UsersDirectoryContent } from './adminUsersView/UsersDirectoryContent'
import { UserRoleInfoDialog } from './adminUsersView/UserRoleInfoDialog'
export type { AdminUsersFilter } from './adminUsersViewTypes'

export function AdminUsersView(props: AdminUsersViewProps) {
    const { users, loading, loadError, page, totalPages, hasPendingMutation, onPageChange, currentUser, currentUserIsSuperAdmin } = props
    const [roleInfoUser, setRoleInfoUser] = useState<User | null>(null)
    return (
        <>
            <ResizableScrollRegion
                storageKey="safeai:users-table-height:v2"
                label="список пользователей"
                preserveUpperContent
                upper={<UsersDirectoryUpper {...props} />}
                footer={
                    !loading
                    && !loadError
                    && users.length > 0
                        ? (
                            <Pagination
                                page={page}
                                totalPages={
                                    totalPages
                                }
                                disabled={
                                    loading
                                    || hasPendingMutation
                                }
                                onPrevious={() =>
                                    onPageChange(
                                        Math.max(
                                            0,
                                            page - 1,
                                        ),
                                    )
                                }
                                onNext={() =>
                                    onPageChange(
                                        page + 1,
                                    )
                                }
                            />
                        )
                        : null
                }
                lowerClassName={
                    users.length === 0 ? 'users-table-card users-table-card--empty' : 'users-table-card'
                }
                viewportClassName={
                    'users-table-scroll'
                }
                defaultHeight={430}
                autoFitHeight={
                    !loading && !loadError
                        ? users.length > 0
                            ? 15 + 59 + users.length * 73 + 56
                            : 175
                        : undefined
                }
                minHeight={72}
                maxHeight={760}
                minUpperHeight={112}
            >
                <UsersDirectoryContent {...props} onShowRoleInfo={setRoleInfoUser} />
            </ResizableScrollRegion>
            <UserRoleInfoDialog
                roleInfoUser={roleInfoUser}
                currentUser={currentUser}
                currentUserIsSuperAdmin={currentUserIsSuperAdmin}
                onClose={() => setRoleInfoUser(null)}
            />
        </>
    )
}

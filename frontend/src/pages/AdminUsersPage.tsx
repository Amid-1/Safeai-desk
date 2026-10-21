// frontend/src/pages/AdminUsersPage.tsx
// Маршрут управления пользователями; публичный интерфейс страницы сохранён.
import PageErrorBoundary from '../shared/ui/PageErrorBoundary'
import { AdminUsersWorkspace } from '../features/admin-users/ui/AdminUsersWorkspace'
import type { AdminUsersPageProps } from '../features/admin-users/ui/AdminUsersWorkspace'
import '../features/admin-users/styles/AdminUsersPage.css'
import '../features/admin-users/styles/AdminUsersProductionControls.css'

function AdminUsersPage(props: AdminUsersPageProps) {
    return (
        <PageErrorBoundary>
            <AdminUsersWorkspace {...props} />
        </PageErrorBoundary>
    )
}

export default AdminUsersPage

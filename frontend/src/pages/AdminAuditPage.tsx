// frontend/src/pages/AdminAuditPage.tsx
// ============================================================
// frontend/src/pages/AdminAuditPage.tsx
// Композиция маршрута: граница ошибок, авторизация и журнал аудита.
// ============================================================
import { useAuth } from '../features/auth/state/useAuth'
import { useAdminAuditController } from '../features/audit/controller/useAdminAuditController'
import { AdminAuditPageView } from '../features/audit/ui/AdminAuditPageView'
import PageErrorBoundary from '../shared/ui/PageErrorBoundary'
import '../features/audit/styles/AdminAuditPage.css'

function AdminAuditPageContent() {
    const controller = useAdminAuditController()
    return <AdminAuditPageView controller={controller} />
}

function AdminAuditRoute() {
    const { currentUser } = useAuth()
    // Перемонтирование очищает и выбранные детали, и загруженные записи,
    // если сменились пользователь, организация или права SUPER_ADMIN.
    const contextKey = [
        currentUser?.id ?? 'anonymous',
        currentUser?.organizationId ?? 'none',
        currentUser?.roles.slice().sort().join(',') ?? 'none',
    ].join(':')

    return <AdminAuditPageContent key={contextKey} />
}

function AdminAuditPage() {
    return <PageErrorBoundary><AdminAuditRoute /></PageErrorBoundary>
}

export default AdminAuditPage

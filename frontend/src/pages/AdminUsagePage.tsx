// frontend/src/pages/AdminUsagePage.tsx
// Тонкий маршрут отчёта об использовании AI; граница ошибок сохранена.
import PageErrorBoundary from '../shared/ui/PageErrorBoundary'
import { AdminUsageWorkspace } from '../features/usage/ui/AdminUsageWorkspace'
import '../features/usage/styles/AdminUsagePage.css'

function AdminUsagePage() {
    return (
        <PageErrorBoundary>
            <AdminUsageWorkspace />
        </PageErrorBoundary>
    )
}

export default AdminUsagePage

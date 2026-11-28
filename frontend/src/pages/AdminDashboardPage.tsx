// frontend/src/pages/AdminDashboardPage.tsx
import PageErrorBoundary from '../shared/ui/PageErrorBoundary'
import { PlatformDashboard } from '../features/usage/ui/PlatformDashboard'
import '../features/usage/styles/AdminUsagePage.css'
import '../features/usage/styles/PlatformDashboard.css'

export default function AdminDashboardPage() {
    return <PageErrorBoundary><PlatformDashboard /></PageErrorBoundary>
}

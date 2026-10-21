// ============================================================
// frontend/src/pages/AdminModelsPage.tsx
// ============================================================
import { AdminModelsPageView } from './adminModels/AdminModelsPageView'
import { useAdminModelsController } from './adminModels/useAdminModelsController'
import './AdminModelsPage.css'
import './AdminModelsPageVisualRefresh.css'
import './AdminModelsProductionEnhancements.css'
import './ModelControlPlaneReadability.css'

function AdminModelsPage() {
    const controller = useAdminModelsController()
    return <AdminModelsPageView controller={controller} />
}

export default AdminModelsPage

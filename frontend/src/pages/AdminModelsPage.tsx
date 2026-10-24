// frontend/src/pages/AdminModelsPage.tsx
// ============================================================
// frontend/src/pages/AdminModelsPage.tsx
// ============================================================
import { AdminModelsPageView } from '../features/models/ui/AdminModelsPageView'
import { useAdminModelsController } from '../features/models/controller/useAdminModelsController'
import '../features/models/styles/AdminModelsPage.css'
import '../features/models/styles/AdminModelsPageVisualRefresh.css'
import '../features/models/styles/AdminModelsProductionEnhancements.css'
import '../features/models/styles/ModelControlPlaneReadability.css'
import '../features/models/styles/AdminModelsSections.css'

function AdminModelsPage() {
    const controller = useAdminModelsController()
    return <AdminModelsPageView controller={controller} />
}

export default AdminModelsPage

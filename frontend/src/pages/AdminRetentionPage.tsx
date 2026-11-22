// frontend/src/pages/AdminRetentionPage.tsx
import PageErrorBoundary from '../shared/ui/PageErrorBoundary'
import { RetentionWorkspace } from '../features/admin/ui/RetentionWorkspace'

export default function AdminRetentionPage() {
    return <PageErrorBoundary><RetentionWorkspace /></PageErrorBoundary>
}

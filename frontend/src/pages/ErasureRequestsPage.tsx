// frontend/src/pages/ErasureRequestsPage.tsx
import type { AuthUser } from '../features/auth/api/authApi'
import { ErasureRequestsWorkspace } from '../features/erasure/ui/ErasureRequestsWorkspace'
import PageErrorBoundary from '../shared/ui/PageErrorBoundary'

export default function ErasureRequestsPage({ user }: { user: AuthUser }) {
    return <PageErrorBoundary><ErasureRequestsWorkspace user={user} /></PageErrorBoundary>
}

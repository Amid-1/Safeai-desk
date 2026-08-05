// ============================================================
// frontend/src/components/PageErrorBoundary.tsx
// ============================================================
import type {
    ReactNode,
} from 'react'

import ErrorBoundary from './ErrorBoundary'

type PageErrorBoundaryProps = {
    children: ReactNode
    resetKey?: string | number
    onReset?: () => void
}

function PageErrorBoundary({
    children,
    resetKey,
    onReset,
}: PageErrorBoundaryProps) {
    return (
        <ErrorBoundary
            variant="page"
            resetKey={resetKey}
            onReset={onReset}
        >
            {children}
        </ErrorBoundary>
    )
}

export default PageErrorBoundary
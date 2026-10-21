// frontend/src/main.tsx
// ============================================================
// frontend/src/main.tsx
// ============================================================
import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './app/App'
import ErrorBoundary from './shared/ui/ErrorBoundary'
import './shared/styles/index.css'

const rootElement = document.getElementById('root')

if (!rootElement) {
    throw new Error('Root element #root не найден')
}

ReactDOM.createRoot(rootElement).render(
    <React.StrictMode>
        <ErrorBoundary>
            <BrowserRouter>
                <App />
            </BrowserRouter>
        </ErrorBoundary>
    </React.StrictMode>,
)

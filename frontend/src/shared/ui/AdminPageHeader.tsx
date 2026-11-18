import type { ReactNode } from 'react'
import './AdminConsole.css'

/** Shared administration header; actions and scope remain owned by the page. */
export function AdminPageHeader({ className = '', children }: {
    className?: string
    children: ReactNode
}) {
    return <header className={`admin-page-header ${className}`}>{children}</header>
}

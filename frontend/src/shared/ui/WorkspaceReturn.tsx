// frontend/src/shared/ui/WorkspaceReturn.tsx
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useEffect } from 'react'
import './WorkspaceReturn.css'
import { safeReturn, isWorkspaceReturnPath } from './workspaceReturnPolicy'
export function WorkspaceLink({to,className,children}: {to:string;className?:string;children:import('react').ReactNode}) {
    const location=useLocation(), navigate=useNavigate()
    return <Link to={to} className={className} onClick={event=>{
        if(event.defaultPrevented||event.button!==0||event.metaKey||event.ctrlKey||event.shiftKey||event.altKey)return
        event.preventDefault();void navigate(to,{state:{from:location.pathname+location.search,scroll:window.scrollY}})
    }}>{children}</Link>
}
export function WorkspaceReturn({ fallback }: { fallback?: string }) {
    const location = useLocation()
    const back = safeReturn(location.state) ?? (fallback ? safeReturn({ from: fallback }) : null)
    if (!back || back.to.split('?')[0] === location.pathname) return null
    return <Link className="er2-back workspace-return" to={back.to} state={{ restoreWorkspaceScroll: back.scroll }}>← {back.label}</Link>
}
export function WorkspaceScrollRestore() {
    const location = useLocation()
    useEffect(() => {
        const value = location.state?.restoreWorkspaceScroll
        if (!isWorkspaceReturnPath(location.pathname) || typeof value !== 'number' || !Number.isFinite(value)) return
        // Retry once after asynchronous page content has had a chance to render; no sensitive state persisted.
        const restore = () => window.scrollTo(0, Math.max(0, Math.min(value, 1000000)))
        const a = window.setTimeout(restore, 0), b = window.setTimeout(restore, 350)
        return () => { window.clearTimeout(a); window.clearTimeout(b) }
    }, [location.key, location.pathname, location.state])
    return null
}

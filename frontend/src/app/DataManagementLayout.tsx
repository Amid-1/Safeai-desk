// frontend/src/app/DataManagementLayout.tsx
import { useEffect, useRef } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../features/auth/state/useAuth'
import { useWorkspaceCapabilities } from '../features/erasure/api/useWorkspaceCapabilities'
import DataManagementIcon from '../shared/ui/DataManagementIcon'
import './DataManagementLayout.css'

export default function DataManagementLayout() {
    const { currentUser } = useAuth()
    const location = useLocation()
    const platform = currentUser?.roles.includes('SUPER_ADMIN') === true
    const context = [currentUser?.id, currentUser?.organizationId,
        currentUser?.roles.slice().sort().join(',')].join(':')
    const { capabilities, failed, loading, retry } = useWorkspaceCapabilities(context + ':' + location.pathname)
    const layout = useRef<HTMLDivElement>(null)
    useEffect(() => {
        const bar = document.querySelector('.topbar')
        if (!bar || typeof ResizeObserver === 'undefined') return
        const measure = () => layout.current?.style.setProperty('--workspace-top', `${bar.getBoundingClientRect().height + 12}px`)
        measure(); const observer = new ResizeObserver(measure); observer.observe(bar)
        return () => observer.disconnect()
    }, [])
    return <div className="data-management-layout" ref={layout}>
        <header className="data-management-header">
            <div className="data-management-title">Управление данными</div>
            <p className="data-management-scope">{platform ? 'Все организации' : 'Ваша организация'}</p>
        </header>
        <div className="data-management-body">
            <nav className="data-management-nav" aria-label="Управление данными">
                {(platform || capabilities?.tenantRegistry) && <NavLink end to="/admin/erasure"><DataManagementIcon name="overview"/>Обзор</NavLink>}
                {capabilities?.platform && <NavLink to="/admin/erasure/registry"><DataManagementIcon name="inbox"/>Заявки</NavLink>}
                {!platform && capabilities?.tenantRegistry && <NavLink to="/admin/erasure/intake"><DataManagementIcon name="inbox"/>Заявки</NavLink>}
                {!capabilities && platform && <span className="data-management-unavailable" aria-disabled="true"><DataManagementIcon name="inbox"/>Заявки</span>}
                {capabilities?.reviewQueue && <NavLink to="/admin/erasure/requests"><DataManagementIcon name="review"/>Согласования</NavLink>}
                {platform && <NavLink to="/admin/retention"><DataManagementIcon name="database"/>Политика хранения</NavLink>}
                {(capabilities?.platform || capabilities?.reviewQueue) &&
                    <NavLink to="/admin/erasure/history"><DataManagementIcon name="history"/>История изменений</NavLink>}
                {!capabilities && platform && <span className="data-management-unavailable" aria-disabled="true"><DataManagementIcon name="history"/>История изменений</span>}
                {failed && <div className="data-management-access-error" role="status">Не удалось проверить доступ.<button type="button" onClick={retry}>Повторить</button></div>}
                {loading && <small role="status">Проверяем доступ…</small>}
            </nav>
            <div className="data-management-main" key={context}><Outlet context={{ capabilities, failed, loading }} /></div>
        </div>
    </div>
}

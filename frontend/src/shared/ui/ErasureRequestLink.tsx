// frontend/src/shared/ui/ErasureRequestLink.tsx
import { useContext } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ErasureEntryContext } from './erasureEntryContext'
import { parseContextTarget, type ContextTarget } from './erasureContextTarget'

export default function ErasureRequestLink({ target }: { target: ContextTarget }) {
    const enabled = useContext(ErasureEntryContext)
    // Keep router hooks in a mounted child: isolated cards may not have a router.
    return enabled && parseContextTarget(target) ? <ContextLink target={target} /> : null
}
function ContextLink({ target }: { target: ContextTarget }) {
    const location = useLocation()
    const navigate = useNavigate()
    return <Link className="erasure-context-link" to="/my/erasure-requests"
        onClick={event => {
            if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
            event.preventDefault()
            void navigate('/my/erasure-requests', { state: { erasureTarget: target, from: location.pathname + location.search, scroll: window.scrollY } })
        }}>
        Запросить удаление
    </Link>
}

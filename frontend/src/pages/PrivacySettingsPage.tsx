// frontend/src/pages/PrivacySettingsPage.tsx
import { Link } from 'react-router-dom'
import { useAuth } from '../features/auth/state/useAuth'
import { useWorkspaceCapabilities } from '../features/erasure/api/useWorkspaceCapabilities'
import '../features/erasure/review/ErasureReview.css'
export default function PrivacySettingsPage() {
    const { currentUser }=useAuth()
    const platform=currentUser?.roles.includes('SUPER_ADMIN')===true
    const context=[currentUser?.id,currentUser?.organizationId,currentUser?.roles.slice().sort().join(',')].join(':')
    const {capabilities,loading,failed,retry}=useWorkspaceCapabilities(context)
    return <section className="er2-page page er2-privacy-page"><header className="er2-heading"><h1>Мои данные и приватность</h1></header>
        <div className="er2-card er2-settings-main"><h2>Моя учётная запись</h2><div className="er2-data-row">
            <div><strong>{currentUser?.fullName||currentUser?.email||(platform?'Системный администратор':'Учётная запись')}</strong>{currentUser?.fullName&&<p>{currentUser.email}</p>}</div><span className="er2-pill">{currentUser?.enabled?'Активна':'Отключена'}</span></div>
            <div className="er2-data-row"><div><strong>Мои обращения</strong>
                {platform?<p>Для системного супер-администратора действует отдельный порядок обращения.</p>:
                    <p>{loading?'Проверяем доступ…':failed?'Не удалось проверить доступ.':capabilities?.personalRequests?'Статусы и история решений.':'Подача обращений сейчас недоступна.'}</p>}</div>
                {!platform&&capabilities?.personalRequests&&<Link className="er2-primary" to="/my/erasure-requests" state={{from:'/settings/privacy'}}>Открыть обращения</Link>}
                {!platform&&failed&&<button type="button" className="er2-action" onClick={retry}>Повторить</button>}</div>
            {!platform&&capabilities?.personalRequests&&<p className="er2-muted">Подача обращения не удаляет данные автоматически.</p>}
        </div></section>
}

// frontend/src/pages/PrivacySettingsPage.tsx
import { Link } from 'react-router-dom'
import { useAuth } from '../features/auth/state/useAuth'
import '../features/erasure/review/ErasureReview.css'

export default function PrivacySettingsPage() {
    const { currentUser } = useAuth()
    return <section className="er2-page page">
        <header className="er2-heading">
            <div className="er2-eyebrow">НАСТРОЙКИ / КОНФИДЕНЦИАЛЬНОСТЬ</div>
            <h1>Мои данные и приватность</h1>
            <p>Управляйте обращениями, касающимися ваших данных. Заявка не удаляет данные автоматически.</p>
        </header>
        <div className="er2-settings-layout">
            <aside className="er2-card er2-settings-menu" aria-label="Разделы настроек">
                <div className="er2-menu-item">Профиль</div>
                <div className="er2-menu-item">Учётная запись и безопасность</div>
                <div className="er2-menu-item er2-menu-item--active" aria-current="page">Данные и приватность</div>
            </aside>
            <div className="er2-card er2-settings-main">
                <h2>Данные и приватность</h2>
                <div className="er2-data-row">
                    <div><strong>Моя учётная запись</strong>
                        <p>Управление доступом и запросами по персональным данным.</p></div>
                    <span className="er2-pill">{currentUser?.enabled ? 'Активна' : 'Отключена'}</span>
                </div>
                <div className="er2-data-row">
                    <div><strong>Запросы на удаление</strong>
                        <p>Просматривайте статус, историю и решения проверяющих.</p></div>
                    <Link className="er2-action" to="/my/erasure-requests">Мои запросы</Link>
                </div>
                <div className="er2-card er2-info">
                    <strong>Удаление аккаунта и удаление данных отличаются</strong>
                    <p>Отключение доступа, удаление активных данных и истечение срока хранения копий
                        проходят разные проверки. Обязательные юридические и финансовые записи могут сохраняться.</p>
                    <Link to="/my/erasure-requests" className="er2-primary">Создать запрос на удаление</Link>
                </div>
                <p className="er2-muted">Физическое удаление в рамках Erasure R1b не выполняется.</p>
            </div>
        </div>
    </section>
}

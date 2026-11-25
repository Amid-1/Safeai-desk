// frontend/src/pages/PrivacySettingsPage.tsx
import { Link } from 'react-router-dom'
import { useAuth } from '../features/auth/state/useAuth'
import '../features/erasure/review/ErasureReview.css'

export default function PrivacySettingsPage() {
    const { currentUser } = useAuth()
    const canSubmit = currentUser?.enabled === true
        && !currentUser.roles.includes('SUPER_ADMIN')
        && currentUser.roles.some(role => role === 'USER' || role === 'ADMIN')
    return <section className="er2-page page er2-privacy-page">
        <header className="er2-heading">
            <div className="er2-eyebrow">НАСТРОЙКИ / КОНФИДЕНЦИАЛЬНОСТЬ</div>
            <h1>Мои данные и приватность</h1>
            <p>Здесь можно подать обращение о своих данных и проследить его рассмотрение.
                Подача запроса сама по себе ничего не удаляет.</p>
        </header>
        <div className="er2-settings-layout">
            <aside className="er2-card er2-settings-menu" aria-label="Разделы настроек">
                <span className="er2-menu-caption">ЛИЧНЫЙ КАБИНЕТ</span>
                <div className="er2-menu-item er2-menu-item--inactive">Профиль
                    <small>Вне текущего раздела</small></div>
                <div className="er2-menu-item er2-menu-item--inactive">Учётная запись и безопасность
                    <small>Вне текущего раздела</small></div>
                <div className="er2-menu-item er2-menu-item--active" aria-current="page">Данные и приватность</div>
            </aside>
            <div className="er2-settings-content">
                <div className="er2-card er2-settings-main">
                    <div className="er2-eyebrow">ВАШИ ДАННЫЕ</div>
                    <h2>Управление приватностью</h2>
                    <div className="er2-data-row">
                        <div><strong>Моя учётная запись</strong>
                            <p>Сведения о доступе к системе и статусе аккаунта.</p></div>
                        <span className="er2-pill">{currentUser?.enabled ? 'Активна' : 'Отключена'}</span>
                    </div>
                    <div className="er2-data-row">
                        <div><strong>Мои запросы на удаление</strong>
                            <p>Черновики, зарегистрированные обращения, решения и история.</p></div>
                        {canSubmit ? <Link className="er2-action" to="/my/erasure-requests">
                            Перейти к запросам</Link> : <span className="er2-pill">Доступ ограничен</span>}
                    </div>
                    <div className="er2-privacy-cta">
                        <div className="er2-eyebrow">ПРАВО НА ОБРАЩЕНИЕ</div>
                        <h3>Нужно удалить данные?</h3>
                        <p>Выберите конкретный объект и проверьте запрос перед подачей.
                            Каждая заявка рассматривается отдельно, с учётом прав и обязательного хранения.</p>
                        {canSubmit ? <Link className="er2-primary" to="/my/erasure-requests">
                            Создать заявку <span aria-hidden="true">→</span></Link> :
                            <p className="er2-muted">Для системного супер-администратора личные запросы через
                                этот интерфейс недоступны. Обращение требует отдельного регламентированного процесса.</p>}
                    </div>
                </div>
                <div className="er2-privacy-process">
                    <div className="er2-privacy-process-item"><span>01</span><strong>Выберите данные</strong>
                        <small>Чат, документ, база знаний или аккаунт</small></div>
                    <div className="er2-privacy-process-item"><span>02</span><strong>Отправьте запрос</strong>
                        <small>Предпросмотр и подтверждение обращения</small></div>
                    <div className="er2-privacy-process-item"><span>03</span><strong>Следите за решением</strong>
                        <small>Статус и история рассмотрения</small></div>
                </div>
                <div className="er2-banner er2-banner--neutral" role="note">
                    <span className="er2-banner-mark" aria-hidden="true">i</span><div>
                        <strong>Удаление аккаунта и удаление данных — разные процессы.</strong>
                        <p>Отключение доступа, очистка активных данных и истечение срока хранения копий
                            проходят отдельные проверки. Физическое удаление в R1b не выполняется.</p>
                    </div>
                </div>
            </div>
        </div>
    </section>
}

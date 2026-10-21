// frontend/src/features/admin-organizations/ui/AdminOrganizationDialogs.tsx
import type { Organization } from '../../../entities/organization/api/organizationApi'
import { normalizeOrganizationConfirmation } from '../../../entities/organization/api/organizationApi'
import { formatDateTime } from '../../../shared/lib/format'
import Modal from '../../../shared/ui/Modal'
import type { DisableOrganizationDialogState } from '../lib/adminOrganizationsSupport'
import { canMutateOrganization } from '../lib/adminOrganizationsSupport'
import {
    OrganizationDetail, OrganizationImpact, OrganizationStatusBadge,
    OrganizationTypeInfo, OrganizationVersionInfo, OrganizationId,
} from './AdminOrganizationsUi'

type AdminOrganizationDialogsProps = {
    detailsOrganization: Organization | null
    detailsError: string
    renameOrganization: Organization | null
    renameValue: string
    renameError: string
    disableDialog: DisableOrganizationDialogState
    enableOrganizationTarget: Organization | null
    hasPendingAction: boolean
    impactLoadingId: string | null
    onCloseDetails: () => void
    onCloseRename: () => void
    onRenameValueChange: (value: string) => void
    onSubmitRename: () => Promise<void>
    onCloseDisable: () => void
    onDisableConfirmationChange: (value: string) => void
    onConfirmDisable: () => Promise<void>
    onCloseEnable: () => void
    onConfirmEnable: () => Promise<void>
    onDisable: (organization: Organization) => Promise<void>
    onEnable: (organization: Organization) => void
    onRename: (organization: Organization) => void
}

export function AdminOrganizationDialogs({
    detailsOrganization, detailsError, renameOrganization, renameValue, renameError,
    disableDialog, enableOrganizationTarget, hasPendingAction, impactLoadingId,
    onCloseDetails, onCloseRename, onRenameValueChange, onSubmitRename,
    onCloseDisable, onDisableConfirmationChange, onConfirmDisable,
    onCloseEnable, onConfirmEnable, onDisable, onEnable, onRename,
}: AdminOrganizationDialogsProps) {
    const mutable = detailsOrganization ? canMutateOrganization(detailsOrganization) : false

    function changeStatus(organization: Organization) {
        // Only one modal may own focus. Existing confirmation performs the mutation.
        onCloseDetails()
        if (organization.enabled) void onDisable(organization)
        else onEnable(organization)
    }

    function renameFromDetails(organization: Organization) {
        onCloseDetails()
        onRename(organization)
    }

    return <>
        {detailsOrganization && <Modal title="Сведения об организации" onClose={onCloseDetails} size="md">
            <div className="org-detail-layout">
                {detailsError && <div className="error" role="alert">{detailsError}</div>}
                <header className="organization-details-heading org-detail-top">
                    <div>
                        <strong>{detailsOrganization.name}</strong>
                        <span className="org-detail-subtitle">{detailsOrganization.type === 'PLATFORM'
                            ? 'Системная организация платформы' : 'Организация клиента'}</span>
                    </div>
                    <OrganizationStatusBadge enabled={detailsOrganization.enabled} />
                </header>
                <section aria-label="Основные сведения об организации">
                    <dl className="organization-details">
                        <OrganizationDetail term="Тип" value={<OrganizationTypeInfo type={detailsOrganization.type} />} />
                        <OrganizationDetail term="Статус" value={<span>
                            <OrganizationStatusBadge enabled={detailsOrganization.enabled} />
                            <span className="org-detail-hint">{detailsOrganization.enabled
                                ? 'Включённые сотрудники этой организации могут входить в SafeAI Desk, если их личные учётные записи активны. Доступ к моделям и функциям зависит от ролей и правил.' : 'Вход пользователей этой организации в SafeAI Desk заблокирован, даже если их личные учётные записи включены.'}</span>
                        </span>} />
                        <OrganizationDetail term="Защита" value={detailsOrganization.protected
                            ? 'Защищена системой — изменение недоступно'
                            : 'Обычная организация клиента'} />
                        <OrganizationDetail term="Версия записи" value={<OrganizationVersionInfo version={detailsOrganization.version} />} />
                        <OrganizationDetail term="Создана" value={formatDateTime(detailsOrganization.createdAt)} />
                        <OrganizationDetail term="Изменена" value={detailsOrganization.updatedAt
                            ? formatDateTime(detailsOrganization.updatedAt) : '—'} />
                    </dl>
                </section>
                <section className="org-detail-technical" aria-label="Технические сведения">
                    <details>
                        <summary>Технические сведения и полный ID</summary>
                        <p>Идентификатор используется для интеграций, аудита и обращения в поддержку.</p>
                        <code className="org-detail-full-id">{detailsOrganization.id}</code>
                        <OrganizationId id={detailsOrganization.id} />
                    </details>
                </section>
                {detailsOrganization.type === 'PLATFORM' && <p className="organization-platform-note">
                    Эта организация необходима для работы SafeAI Desk. Ее нельзя переименовать,
                    отключить или включить обычными действиями администрирования.
                </p>}
                <div className="modal-actions org-detail-actions">
                    {mutable && <>
                        <button type="button" className="secondary-button" disabled={hasPendingAction}
                                onClick={() => renameFromDetails(detailsOrganization)}>Переименовать</button>
                        <button type="button"
                                className={detailsOrganization.enabled ? 'danger-button' : 'secondary-button'}
                                disabled={hasPendingAction || impactLoadingId === detailsOrganization.id}
                                onClick={() => changeStatus(detailsOrganization)}>
                            {impactLoadingId === detailsOrganization.id ? 'Проверка…'
                                : detailsOrganization.enabled ? 'Отключить организацию' : 'Включить организацию'}
                        </button>
                    </>}
                    <button type="button" className="secondary-button" onClick={onCloseDetails}>Закрыть</button>
                </div>
            </div>
        </Modal>}

        {renameOrganization && <Modal
            title={'Переименовать организацию: ' + renameOrganization.name}
            onClose={onCloseRename} closeDisabled={hasPendingAction}>
            <form className="form" onSubmit={event => { event.preventDefault(); void onSubmitRename() }}>
                <label>Новое название
                    <input value={renameValue} onChange={event => onRenameValueChange(event.target.value)}
                           maxLength={255} required autoFocus disabled={hasPendingAction} />
                </label>
                {renameError && <div className="error" role="alert" aria-live="assertive">{renameError}</div>}
                <div className="modal-actions">
                    <button type="button" className="secondary-button" disabled={hasPendingAction}
                            onClick={onCloseRename}>Отмена</button>
                    <button type="submit" disabled={hasPendingAction || !renameValue.trim()}>
                        {hasPendingAction ? 'Сохранение…' : 'Сохранить'}</button>
                </div>
            </form>
        </Modal>}

        {disableDialog && <Modal title="Отключить организацию"
            onClose={onCloseDisable} closeDisabled={hasPendingAction} size="sm">
            <p className="organization-dialog-lead">Организация: <strong>{disableDialog.organization.name}</strong></p>
            <dl className="organization-impact">
                <OrganizationImpact term="Включённых пользователей" value={disableDialog.impact.enabledUsers} />
                <OrganizationImpact term="Администраторов" value={disableDialog.impact.administrators} />
                <OrganizationImpact term="Действующих сеансов продления входа" value={disableDialog.impact.activeRefreshSessions} />
                <OrganizationImpact term="Незавершённых операций чата" value={disableDialog.impact.activeChatOperations} />
                {disableDialog.impact.startedPhysicalAttempts !== undefined &&
                    <OrganizationImpact term="Начатых обращений к AI-провайдеру"
                        value={disableDialog.impact.startedPhysicalAttempts} />}
                {disableDialog.impact.uncertainPhysicalAttempts !== undefined &&
                    <OrganizationImpact term="Обращений с неопределённым результатом"
                        value={disableDialog.impact.uncertainPhysicalAttempts} />}
            </dl>
            <p className="org-impact-explanation" role="note">
                Показатели рассчитаны сервером автоматически на момент проверки.
                Ноль означает, что подходящих записей сейчас не найдено.
                Сеансы продления входа — не число сотрудников онлайн.
                Количество незавершённых операций чата также не равно числу внешних AI-запросов.
                Записи с неопределённым результатом могут быть историческими и не доказывают,
                что запрос выполняется сейчас или будет оплачен.
            </p>
            <div className="danger-notice">
                Отключение запретит доступ организации к SafeAI Desk и отзовёт сеансы входа.
                Данные пользователей и история не удаляются. Уже отправленные внешнему
                AI-провайдеру запросы не всегда возможно отменить.
                Итоговое решение принимает сервер повторно при подтверждении.
            </div>
            <form className="form organization-disable-form" onSubmit={event => {
                event.preventDefault()
                void onConfirmDisable()
            }}>
                <label>Введите название организации для подтверждения
                    <input value={disableDialog.confirmationName}
                           onChange={event => onDisableConfirmationChange(event.target.value)}
                           autoComplete="off" required disabled={hasPendingAction} />
                    <span className="organization-confirmation-hint">
                        Регистр, вид кавычек и лишние пробелы не учитываются. Например:
                        {' '}<strong>ООО &quot;Зил&quot;</strong> можно ввести как <strong>ооо зил</strong>
                        {' '}или <strong>ООО «ЗИЛ»</strong>. При этом ООО/АО и название должны совпадать.
                    </span>
                </label>
                <div className="modal-actions">
                    <button type="button" className="secondary-button" disabled={hasPendingAction}
                            onClick={onCloseDisable}>Отмена</button>
                    <button type="submit" className="danger-button" disabled={hasPendingAction
                        || normalizeOrganizationConfirmation(disableDialog.confirmationName)
                        !== normalizeOrganizationConfirmation(disableDialog.organization.name)}>
                        {hasPendingAction ? 'Отключение…' : 'Отключить организацию'}
                    </button>
                </div>
            </form>
        </Modal>}

        {enableOrganizationTarget && <Modal title="Включить организацию"
            onClose={onCloseEnable} closeDisabled={hasPendingAction} size="sm">
            <p className="organization-dialog-lead">Включить организацию{' '}
                <strong>{enableOrganizationTarget.name}</strong>?</p>
            <p className="org-dialog-help">На уровне организации снова будет разрешён вход в SafeAI Desk.
                Отключённые пользователи не включатся автоматически.
                Доступ к моделям и функциям по-прежнему будет зависеть от ролей и правил.
                Выполните действие только после проверки.</p>
            <div className="modal-actions">
                <button type="button" className="secondary-button" disabled={hasPendingAction}
                        onClick={onCloseEnable}>Отмена</button>
                <button type="button" disabled={hasPendingAction}
                        onClick={() => void onConfirmEnable()}>
                    {hasPendingAction ? 'Включение…' : 'Включить'}</button>
            </div>
        </Modal>}
    </>
}

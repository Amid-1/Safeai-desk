import type {
    Organization,
} from '../../../api/organizationApi'
import {
    normalizeOrganizationConfirmation,
} from '../../../api/organizationApi'
import {
    formatDateTime,
} from '../../../utils/format'
import Modal from '../../Modal'
import type {
    DisableOrganizationDialogState,
} from '../../../pages/adminOrganizationsSupport'
import {
    OrganizationDetail,
    OrganizationImpact,
    OrganizationStatusBadge,
    OrganizationTypeBadge,
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
    onCloseDetails: () => void
    onCloseRename: () => void
    onRenameValueChange: (value: string) => void
    onSubmitRename: () => Promise<void>
    onCloseDisable: () => void
    onDisableConfirmationChange: (value: string) => void
    onConfirmDisable: () => Promise<void>
    onCloseEnable: () => void
    onConfirmEnable: () => Promise<void>
}

export function AdminOrganizationDialogs({
    detailsOrganization,
    detailsError,
    renameOrganization,
    renameValue,
    renameError,
    disableDialog,
    enableOrganizationTarget,
    hasPendingAction,
    onCloseDetails,
    onCloseRename,
    onRenameValueChange,
    onSubmitRename,
    onCloseDisable,
    onDisableConfirmationChange,
    onConfirmDisable,
    onCloseEnable,
    onConfirmEnable,
}: AdminOrganizationDialogsProps) {
    return (
        <>
            {detailsOrganization && (
                <Modal
                    title="Подробнее об организации"
                    onClose={onCloseDetails}
                    size="md"
                >
                    {detailsError && (
                        <div
                            className="error"
                            role="alert"
                        >
                            {detailsError}
                        </div>
                    )}

                    <div className="organization-details-heading">
                        <div>
                            <strong>
                                {detailsOrganization.name}
                            </strong>
                            <span>
                                {detailsOrganization.id}
                            </span>
                        </div>

                        <OrganizationStatusBadge
                            enabled={
                                detailsOrganization.enabled
                            }
                        />
                    </div>

                    <dl className="organization-details">
                        <OrganizationDetail
                            term="Тип"
                            value={
                                <OrganizationTypeBadge
                                    type={detailsOrganization.type}
                                />
                            }
                        />
                        <OrganizationDetail
                            term="Защита"
                            value={
                                detailsOrganization.protected
                                    ? 'Защищённая системная организация'
                                    : 'Обычная клиентская организация'
                            }
                        />
                        <OrganizationDetail
                            term="Версия"
                            value={`v${detailsOrganization.version}`}
                        />
                        <OrganizationDetail
                            term="Дата создания"
                            value={
                                formatDateTime(
                                    detailsOrganization.createdAt,
                                )
                            }
                        />
                        <OrganizationDetail
                            term="Последнее изменение"
                            value={
                                detailsOrganization.updatedAt
                                    ? formatDateTime(
                                        detailsOrganization.updatedAt,
                                    )
                                    : '—'
                            }
                        />
                    </dl>

                    {detailsOrganization.type
                        === 'PLATFORM'
                        && (
                            <div className="organization-platform-note">
                                PLATFORM используется самой платформой
                                SafeAI Desk и защищена от обычных
                                переименований, включения и отключения.
                            </div>
                        )}
                </Modal>
            )}

            {renameOrganization && (
                <Modal
                    title={
                        'Переименовать организацию: '
                        + renameOrganization.name
                    }
                    onClose={onCloseRename}
                    closeDisabled={hasPendingAction}
                >
                    <form
                        className="form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void onSubmitRename()
                        }}
                    >
                        <label>
                            Новое название
                            <input
                                value={renameValue}
                                onChange={(event) =>
                                    onRenameValueChange(
                                        event.target.value,
                                    )
                                }
                                maxLength={255}
                                required
                                autoFocus
                                disabled={hasPendingAction}
                            />
                        </label>

                        {renameError && (
                            <div
                                className="error"
                                role="alert"
                                aria-live="assertive"
                            >
                                {renameError}
                            </div>
                        )}

                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={hasPendingAction}
                                onClick={onCloseRename}
                            >
                                Отмена
                            </button>

                            <button
                                type="submit"
                                disabled={
                                    hasPendingAction
                                    || !renameValue.trim()
                                }
                            >
                                {hasPendingAction
                                    ? 'Сохранение...'
                                    : 'Сохранить'}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {disableDialog && (
                <Modal
                    title="Отключить организацию"
                    onClose={onCloseDisable}
                    closeDisabled={hasPendingAction}
                    size="sm"
                >
                    <p className="organization-dialog-lead">
                        Организация:{' '}
                        <strong>
                            {disableDialog.organization.name}
                        </strong>
                    </p>

                    <dl className="organization-impact">
                        <OrganizationImpact
                            term="Включённых пользователей"
                            value={
                                disableDialog.impact.enabledUsers
                            }
                        />
                        <OrganizationImpact
                            term="Администраторов"
                            value={
                                disableDialog.impact.administrators
                            }
                        />
                        <OrganizationImpact
                            term="Активных refresh-сессий"
                            value={
                                disableDialog.impact.activeRefreshSessions
                            }
                        />
                        <OrganizationImpact
                            term="Активных операций чата"
                            value={
                                disableDialog.impact.activeChatOperations
                            }
                        />
                    </dl>

                    <div className="danger-notice">
                        Будет запущено отключение организации
                        и отзыв активных сессий. Операция может
                        занять некоторое время.
                    </div>

                    <form
                        className="form organization-disable-form"
                        onSubmit={(event) => {
                            event.preventDefault()
                            void onConfirmDisable()
                        }}
                    >
                        <label>
                            Введите название организации
                            для подтверждения
                            <input
                                value={
                                    disableDialog.confirmationName
                                }
                                onChange={(event) =>
                                    onDisableConfirmationChange(
                                        event.target.value,
                                    )
                                }
                                autoComplete="off"
                                required
                                disabled={hasPendingAction}
                            />

                            <span className="organization-confirmation-hint">
                                Регистр, вид кавычек и лишние пробелы
                                не учитываются. Например:{' '}
                                <strong>ООО &quot;Зил&quot;</strong>{' '}
                                можно ввести как{' '}
                                <strong>ооо зил</strong>{' '}
                                или{' '}
                                <strong>ООО «ЗИЛ»</strong>.
                                При этом ООО/АО и само название должны
                                совпадать без опечаток.
                            </span>
                        </label>

                        <div className="modal-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                disabled={hasPendingAction}
                                onClick={onCloseDisable}
                            >
                                Отмена
                            </button>

                            <button
                                type="submit"
                                className="danger-button"
                                disabled={
                                    hasPendingAction
                                    || normalizeOrganizationConfirmation(
                                        disableDialog.confirmationName,
                                    )
                                        !== normalizeOrganizationConfirmation(
                                            disableDialog.organization.name,
                                        )
                                }
                            >
                                {hasPendingAction
                                    ? 'Отключение...'
                                    : 'Отключить организацию'}
                            </button>
                        </div>
                    </form>
                </Modal>
            )}

            {enableOrganizationTarget && (
                <Modal
                    title="Включить организацию"
                    onClose={onCloseEnable}
                    closeDisabled={hasPendingAction}
                    size="sm"
                >
                    <p className="organization-dialog-lead">
                        Включить организацию{' '}
                        <strong>
                            {enableOrganizationTarget.name}
                        </strong>
                        ?
                    </p>

                    <div className="modal-actions">
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={hasPendingAction}
                            onClick={onCloseEnable}
                        >
                            Отмена
                        </button>

                        <button
                            type="button"
                            disabled={hasPendingAction}
                            onClick={() =>
                                void onConfirmEnable()
                            }
                        >
                            {hasPendingAction
                                ? 'Включение...'
                                : 'Включить'}
                        </button>
                    </div>
                </Modal>
            )}
        </>
    )
}

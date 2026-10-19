import {
    useRef,
    useState,
} from 'react'

import type {
    KnowledgeBaseVisibility,
} from '../../api/knowledgeApi'

import {
    ApiError,
    getApiErrorMessage,
} from '../../api/http'

import Modal from '../Modal'
import './KnowledgeBaseFormModal.css'

export type KnowledgeBaseFormValue = {
    name: string
    description: string
    visibility: KnowledgeBaseVisibility
    enabled: boolean
}

type KnowledgeBaseFormModalProps = {
    title: string
    submitText: string
    initial: KnowledgeBaseFormValue
    busy: boolean
    allowEnabled?: boolean
    onClose: () => void
    onReload?: () => Promise<void>
    onSubmit:
        (
            form: KnowledgeBaseFormValue,
        ) => Promise<void>
}

const MAX_NAME_LENGTH = 255
const MAX_DESCRIPTION_LENGTH = 2_000

function KnowledgeBaseFormModal({
    title,
    submitText,
    initial,
    busy,
    allowEnabled = false,
    onClose,
    onReload,
    onSubmit,
}: KnowledgeBaseFormModalProps) {
    const [
        form,
        setForm,
    ] = useState<KnowledgeBaseFormValue>(
        initial,
    )

    const [
        error,
        setError,
    ] = useState('')
    const [conflict, setConflict] = useState(false)
    const [reloading, setReloading] = useState(false)
    const [submitting, setSubmitting] = useState(false)
    const reloadInFlightRef = useRef(false)

    const visibilityNarrowed =
        allowEnabled
        && initial.visibility === 'ORGANIZATION'
        && form.visibility === 'MEMBERS'
    const disabling =
        allowEnabled && initial.enabled && !form.enabled

    /*
     * busy приходит от parent и может обновиться
     * не в тот же event loop tick.
     *
     * Локальный ref дополнительно защищает
     * от двойного submit.
     */
    const submitInFlightRef =
        useRef(false)

    const normalizedName =
        form.name.trim()

    const submitDisabled =
        busy || submitting || reloading || conflict
        || normalizedName.length === 0

    async function submitForm():
        Promise<void> {
        if (
            busy
            || reloading
            || conflict
            || submitInFlightRef.current
        ) {
            return
        }

        const name =
            form.name.trim()

        if (!name) {
            setError(
                'Введите название базы знаний.',
            )
            return
        }

        if (
            name.length
            > MAX_NAME_LENGTH
        ) {
            setError(
                `Название базы знаний не должно превышать ${MAX_NAME_LENGTH} символов.`,
            )
            return
        }

        if (
            form.description.length
            > MAX_DESCRIPTION_LENGTH
        ) {
            setError(
                `Описание не должно превышать ${MAX_DESCRIPTION_LENGTH} символов.`,
            )
            return
        }

        submitInFlightRef.current = true
        setSubmitting(true)
        setError('')

        try {
            await onSubmit({
                name,
                description:
                    form.description,
                visibility:
                    form.visibility,
                enabled:
                    form.enabled,
            })
        } catch (submitError) {
            // Duplicate name also returns 409: only stale optimistic-version
            // conflicts require a reload and block accidental resubmission.
            if (
                onReload
                && submitError instanceof ApiError
                && submitError.status === 409
                && /expectedVersion|верси|измен[её]н другим запросом/iu.test(
                    submitError.message,
                )
            ) {
                setConflict(true)
                setError(
                    'База знаний была изменена параллельно. '
                    + 'Сохранение не выполнено. Обновите данные '
                    + 'перед повторным редактированием.',
                )
            } else {
                setError(getApiErrorMessage(
                    submitError,
                    'Не удалось сохранить базу знаний.',
                ))
            }
        } finally {
            submitInFlightRef.current = false
            setSubmitting(false)
        }
    }

    async function reloadAfterConflict(): Promise<void> {
        if (!onReload || reloadInFlightRef.current || busy || submitting) {
            return
        }
        reloadInFlightRef.current = true
        setReloading(true)
        try {
            await onReload()
            // Parent remounts this form using the new resource version.
        } catch (reloadError) {
            setError(getApiErrorMessage(
                reloadError,
                'Не удалось загрузить актуальные настройки. Повторите попытку.',
            ))
        } finally {
            reloadInFlightRef.current = false
            setReloading(false)
        }
    }

    function updateName(
        value: string,
    ): void {
        setForm(
            (current) => ({
                ...current,
                name: value,
            }),
        )

        if (error) {
            setError('')
        }
    }

    function updateDescription(
        value: string,
    ): void {
        setForm(
            (current) => ({
                ...current,
                description: value,
            }),
        )
    }

    function updateVisibility(
        value: string,
    ): void {
        const visibility =
            parseKnowledgeBaseVisibility(
                value,
            )

        if (visibility === null) {
            setError(
                'Выбран неизвестный режим доступа.',
            )
            return
        }

        setForm(
            (current) => ({
                ...current,
                visibility,
            }),
        )

        if (error) {
            setError('')
        }
    }

    function updateEnabled(
        enabled: boolean,
    ): void {
        setForm(
            (current) => ({
                ...current,
                enabled,
            }),
        )
    }

    return (
        <Modal
            title={title}
            onClose={onClose}
            closeDisabled={busy || submitting || reloading}
            size="md"
        >
            <form
                className="form"
                onSubmit={(event) => {
                    event.preventDefault()

                    void submitForm()
                }}
                noValidate
            >
                <label
                    htmlFor={
                        'knowledge-base-name'
                    }
                >
                    Название

                    <input
                        id={
                            'knowledge-base-name'
                        }
                        type="text"
                        value={form.name}
                        maxLength={
                            MAX_NAME_LENGTH
                        }
                        disabled={busy || submitting || reloading}
                        required
                        autoFocus
                        autoComplete="off"
                        onChange={(event) =>
                            updateName(
                                event.target.value,
                            )
                        }
                    />
                </label>

                <label
                    htmlFor={
                        'knowledge-base-description'
                    }
                >
                    Описание

                    <textarea
                        id={
                            'knowledge-base-description'
                        }
                        value={
                            form.description
                        }
                        maxLength={
                            MAX_DESCRIPTION_LENGTH
                        }
                        rows={5}
                        disabled={busy || submitting || reloading}
                        onChange={(event) =>
                            updateDescription(
                                event.target.value,
                            )
                        }
                    />
                </label>

                <fieldset className="knowledge-visibility-picker">
                    <legend>
                        Кто может использовать базу
                    </legend>

                    <p>
                        Доступ действует только внутри вашей организации.
                        Пользователи других организаций никогда не добавляются
                        автоматически.
                    </p>

                    <label
                        className={
                            form.visibility === 'ORGANIZATION'
                                ? 'knowledge-visibility-option knowledge-visibility-option--selected'
                                : 'knowledge-visibility-option'
                        }
                    >
                        <input
                            type="radio"
                            name="knowledge-base-visibility"
                            value="ORGANIZATION"
                            checked={form.visibility === 'ORGANIZATION'}
                            disabled={busy || submitting || reloading}
                            onChange={(event) =>
                                updateVisibility(event.target.value)
                            }
                        />

                        <span>
                            <strong>Все сотрудники организации</strong>
                            <small>
                                Активные сотрудники получают доступ к чтению,
                                скачиванию и использованию документов в AI-чате.
                                Загрузка и управление требуют отдельно назначенных прав.
                            </small>
                        </span>
                    </label>

                    <label
                        className={
                            form.visibility === 'MEMBERS'
                                ? 'knowledge-visibility-option knowledge-visibility-option--selected'
                                : 'knowledge-visibility-option'
                        }
                    >
                        <input
                            type="radio"
                            name="knowledge-base-visibility"
                            value="MEMBERS"
                            checked={form.visibility === 'MEMBERS'}
                            disabled={busy || submitting || reloading}
                            onChange={(event) =>
                                updateVisibility(event.target.value)
                            }
                        />

                        <span>
                            <strong>Только приглашённые сотрудники</strong>
                            <small>
                                Новые обращения разрешены приглашённым участникам
                                с соответствующими правами. Администраторы организации
                                сохраняют административный доступ.
                            </small>
                        </span>
                    </label>
                </fieldset>

                <p className="knowledge-base-form__access-note">
                    Изменение видимости применяется к новым обращениям к базе
                    и документам. Уже сохранённые сообщения чатов и история
                    ответов не удаляются. Доступ к будущему поиску проверяет backend.
                </p>

                {(visibilityNarrowed || disabling) && (
                    <div className="knowledge-base-form__warning" role="note">
                        <strong>Изменение доступности</strong>
                        {visibilityNarrowed && (
                            <p>
                                Сотрудники без приглашения больше не смогут
                                обращаться к базе в новых запросах.
                            </p>
                        )}
                        {disabling && (
                            <p>
                                Отключённая база станет недоступна обычным
                                сотрудникам для новых операций.
                            </p>
                        )}
                        <p>Ранее сохранённые ответы останутся в истории чатов.</p>
                    </div>
                )}

                {allowEnabled && (
                    <label
                        className={
                            'knowledge-checkbox'
                        }
                    >
                        <input
                            type="checkbox"
                            checked={
                                form.enabled
                            }
                            disabled={busy || submitting || reloading}
                            onChange={(event) =>
                                updateEnabled(
                                    event.target
                                        .checked,
                                )
                            }
                        />

                        <span>
                            База знаний активна
                        </span>
                    </label>
                )}

                {error && (
                    <div
                        className="error"
                        role="alert"
                        aria-live="assertive"
                    >
                        {error}
                    </div>
                )}

                {conflict && onReload && (
                    <div className="knowledge-base-form__conflict" role="status">
                        <p>
                            При обновлении несохранённые изменения этой формы
                            будут заменены актуальными данными с сервера.
                        </p>
                        <button
                            type="button"
                            className="secondary-button"
                            disabled={busy || submitting || reloading}
                            onClick={() => void reloadAfterConflict()}
                        >
                            {reloading ? 'Обновляем…' : 'Загрузить актуальные настройки'}
                        </button>
                    </div>
                )}

                <div
                    className={
                        'modal-actions'
                    }
                >
                    <button
                        type="button"
                        className={
                            'secondary-button'
                        }
                        disabled={busy || submitting || reloading}
                        onClick={onClose}
                    >
                        Отмена
                    </button>

                    <button
                        type="submit"
                        disabled={
                            submitDisabled
                        }
                    >
                        {busy || submitting
                            ? 'Сохранение...'
                            : submitText}
                    </button>
                </div>
            </form>
        </Modal>
    )
}

function parseKnowledgeBaseVisibility(
    value: string,
): KnowledgeBaseVisibility | null {
    switch (value) {
        case 'ORGANIZATION':
        case 'MEMBERS':
            return value

        default:
            return null
    }
}

export default KnowledgeBaseFormModal

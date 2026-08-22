import {
    useRef,
    useState,
} from 'react'

import type {
    KnowledgeBaseVisibility,
} from '../../api/knowledgeApi'

import {
    getApiErrorMessage,
} from '../../api/http'

import Modal from '../Modal'

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
        busy
        || normalizedName.length === 0

    async function submitForm():
        Promise<void> {
        if (
            busy
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
            setError(
                getApiErrorMessage(
                    submitError,
                    'Не удалось сохранить базу знаний.',
                ),
            )
        } finally {
            submitInFlightRef.current =
                false
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
            closeDisabled={busy}
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
                        disabled={busy}
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
                        disabled={busy}
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
                            disabled={busy}
                            onChange={(event) =>
                                updateVisibility(event.target.value)
                            }
                        />

                        <span>
                            <strong>Все сотрудники организации</strong>
                            <small>
                                Любой активный сотрудник вашей организации может
                                находить и использовать эту базу в AI-чате.
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
                            disabled={busy}
                            onChange={(event) =>
                                updateVisibility(event.target.value)
                            }
                        />

                        <span>
                            <strong>Только приглашённые сотрудники</strong>
                            <small>
                                Базу видят только сотрудники, которых добавили
                                на вкладке «Доступ».
                            </small>
                        </span>
                    </label>
                </fieldset>

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
                            disabled={busy}
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
                        disabled={busy}
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
                        {busy
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

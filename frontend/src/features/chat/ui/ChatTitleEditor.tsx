// frontend/src/features/chat/ui/ChatTitleEditor.tsx
import {
    useEffect,
    useRef,
    useState,
} from 'react'
import type {
    KeyboardEvent,
} from 'react'
import {
    getApiErrorMessage,
} from '../../../shared/api/http'
import './ChatTitleEditor.css'

const MAX_TITLE_LENGTH = 255

type ChatTitleEditorProps = {
    title: string
    onSave: (title: string) => Promise<void>
    initiallyEditing?: boolean
    onCancelEditing?: () => void
    onSaved?: () => void
}

function ChatTitleEditor({
    title,
    onSave,
    initiallyEditing = false,
    onCancelEditing,
    onSaved,
}: ChatTitleEditorProps) {
    const [editing, setEditing] =
        useState(initiallyEditing)
    const [draft, setDraft] =
        useState(title)
    const [saving, setSaving] =
        useState(false)
    const [error, setError] =
        useState('')

    const inputRef =
        useRef<HTMLInputElement | null>(null)

    useEffect(() => {
        if (!editing) {
            setDraft(title)
        }
    }, [title, editing])

    useEffect(() => {
        if (editing) {
            inputRef.current?.focus()
            inputRef.current?.select()
        }
    }, [editing])

    function startEditing() {
        setDraft(title)
        setError('')
        setEditing(true)
    }

    function cancelEditing() {
        if (saving) {
            return
        }
        setDraft(title)
        setError('')
        setEditing(false)
        onCancelEditing?.()
    }

    async function save() {
        if (saving) {
            return
        }

        const normalized = draft
            .replace(/\s+/g, ' ')
            .trim()

        if (!normalized) {
            setError('Введите название чата.')
            return
        }

        if (Array.from(normalized).length > MAX_TITLE_LENGTH) {
            setError(
                `Название не должно превышать ${MAX_TITLE_LENGTH} символов.`,
            )
            return
        }

        if (normalized === title) {
            setEditing(false)
            setError('')
            onSaved?.()
            return
        }

        setSaving(true)
        setError('')

        try {
            await onSave(normalized)
            setEditing(false)
            onSaved?.()
        } catch (saveError) {
            setError(
                getApiErrorMessage(
                    saveError,
                    'Не удалось переименовать чат.',
                ),
            )
        } finally {
            setSaving(false)
        }
    }

    function handleKeyDown(
        event: KeyboardEvent<HTMLInputElement>,
    ) {
        if (event.key === 'Escape') {
            event.preventDefault()
            event.stopPropagation()
            cancelEditing()
            return
        }

        if (event.key === 'Enter') {
            event.preventDefault()
            void save()
        }
    }

    if (!editing) {
        return (
            <div className="chat-title-view">
                <h2 title={title}>{title}</h2>
                <button
                    type="button"
                    className="chat-title-edit-button"
                    aria-label={`Переименовать чат «${title}»`}
                    title="Переименовать чат"
                    onClick={startEditing}
                >
                    <span aria-hidden="true">✎</span>
                </button>
            </div>
        )
    }

    return (
        <div className="chat-title-editor">
            <div className="chat-title-editor__row">
                <input
                    ref={inputRef}
                    value={draft}
                    // Unicode code points are validated above and on the backend.
                    maxLength={MAX_TITLE_LENGTH * 2}
                    autoComplete="off"
                    aria-label="Название чата"
                    aria-invalid={Boolean(error)}
                    disabled={saving}
                    onChange={(event) => {
                        setDraft(event.target.value)
                        setError('')
                    }}
                    onKeyDown={handleKeyDown}
                />

                <button
                    type="button"
                    className="chat-title-save-button"
                    disabled={saving}
                    onClick={() => void save()}
                >
                    {saving ? 'Сохранение…' : 'Сохранить'}
                </button>

                <button
                    type="button"
                    className="chat-title-cancel-button"
                    disabled={saving}
                    onClick={cancelEditing}
                >
                    Отмена
                </button>
            </div>

            {error && (
                <small
                    className="chat-title-editor__error"
                    role="alert"
                >
                    {error}
                </small>
            )}
        </div>
    )
}

export default ChatTitleEditor

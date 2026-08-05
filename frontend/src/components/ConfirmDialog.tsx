// ============================================================
// frontend/src/components/ConfirmDialog.tsx
// ============================================================

import {
    useEffect,
    useId,
    useRef,
    useState,
} from 'react'
import Modal from './Modal'

type ConfirmDialogProps = {
    title: string
    message: string

    confirmText?: string
    danger?: boolean
    loading?: boolean

    onConfirm:
        () => void | Promise<void>
    onCancel: () => void

    onConfirmError?:
        (error: unknown) => void
}

function ConfirmDialog({
    title,
    message,
    confirmText = 'Подтвердить',
    danger = false,
    loading = false,
    onConfirm,
    onCancel,
    onConfirmError,
}: ConfirmDialogProps) {
    const descriptionId = useId()

    const cancelButtonRef =
        useRef<HTMLButtonElement | null>(
            null,
        )

    const confirmingRef =
        useRef(false)

    const loadingRef =
        useRef(loading)

    const mountedRef =
        useRef(true)

    loadingRef.current = loading

    useEffect(() => {
        return () => {
            mountedRef.current = false
        }
    }, [])

    const [
        internalLoading,
        setInternalLoading,
    ] = useState(false)

    const [
        internalError,
        setInternalError,
    ] = useState('')

    const busy =
        loading || internalLoading

    async function handleConfirm() {
        if (
            confirmingRef.current
            || loadingRef.current
        ) {
            return
        }

        confirmingRef.current = true
        setInternalLoading(true)
        setInternalError('')

        try {
            await onConfirm()

            // Блокирует второй event в том же frame,
            // даже если parent handler вернул void.
            await nextAnimationFrame()
        } catch (error) {
            if (mountedRef.current) {
                setInternalError(
                    'Операция не выполнена. '
                    + 'Проверьте сообщение страницы и повторите действие.',
                )
            }
            try {
                onConfirmError?.(error)
            } catch {
                // Error reporting callback не должен
                // создавать unhandled rejection.
            }
        } finally {
            confirmingRef.current = false

            if (mountedRef.current) {
                setInternalLoading(false)
            }
        }
    }

    function handleCancel() {
        if (!busy) {
            onCancel()
        }
    }

    return (
        <Modal
            title={title}
            onClose={handleCancel}
            closeDisabled={busy}
            size="sm"
            descriptionId={
                descriptionId
            }
            initialFocusRef={
                cancelButtonRef
            }
        >
            <p
                id={descriptionId}
                className={
                    'confirm-dialog__message'
                }
            >
                {message}
            </p>

            {internalError && (
                <div
                    className="error"
                    role="alert"
                    aria-live="assertive"
                >
                    {internalError}
                </div>
            )}

            <div className="modal-actions">
                <button
                    ref={cancelButtonRef}
                    type="button"
                    className={
                        'secondary-button'
                    }
                    disabled={busy}
                    onClick={handleCancel}
                >
                    Отмена
                </button>

                <button
                    type="button"
                    className={
                        danger
                            ? 'danger-button'
                            : undefined
                    }
                    disabled={busy}
                    onClick={() =>
                        void handleConfirm()
                    }
                >
                    {busy
                        ? 'Выполняется...'
                        : confirmText}
                </button>
            </div>
        </Modal>
    )
}

function nextAnimationFrame():
    Promise<void> {
    return new Promise(
        (resolve) => {
            window.requestAnimationFrame(
                () => resolve(),
            )
        },
    )
}

export default ConfirmDialog
// ============================================================
// frontend/src/components/admin/Modal.tsx
// ============================================================
import {
    useEffect,
    useId,
    useRef,
} from 'react'
import type {
    MouseEvent as ReactMouseEvent,
    ReactNode,
} from 'react'

type Props = {
    title: string
    children: ReactNode
    onClose: () => void
    closeDisabled?: boolean
    size?: 'sm' | 'md' | 'lg'
}

function Modal({
                   title,
                   children,
                   onClose,
                   closeDisabled = false,
                   size = 'md',
               }: Props) {
    const titleId = useId()
    const cardRef = useRef<HTMLDivElement>(null)
    const previousActiveElementRef = useRef<HTMLElement | null>(null)

    useEffect(() => {
        previousActiveElementRef.current =
            document.activeElement instanceof HTMLElement
                ? document.activeElement
                : null

        const card = cardRef.current
        const firstFocusable = card?.querySelector<HTMLElement>(
            'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
        )

        firstFocusable?.focus()

        function handleKeyDown(event: KeyboardEvent) {
            if (event.key === 'Escape') {
                if (!closeDisabled) {
                    onClose()
                }
                return
            }

            if (event.key !== 'Tab' || !cardRef.current) {
                return
            }

            const focusable = Array.from(
                cardRef.current.querySelectorAll<HTMLElement>(
                    'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
                )
            )

            if (focusable.length === 0) {
                return
            }

            const first = focusable[0]
            const last = focusable[focusable.length - 1]

            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault()
                last.focus()
            } else if (
                !event.shiftKey
                && document.activeElement === last
            ) {
                event.preventDefault()
                first.focus()
            }
        }

        document.addEventListener('keydown', handleKeyDown)
        document.body.classList.add('modal-open')

        return () => {
            document.removeEventListener('keydown', handleKeyDown)
            document.body.classList.remove('modal-open')
            previousActiveElementRef.current?.focus()
        }
    }, [closeDisabled, onClose])

    function handleBackdropClick(
        event: ReactMouseEvent<HTMLDivElement>
    ) {
        if (
            event.target === event.currentTarget
            && !closeDisabled
        ) {
            onClose()
        }
    }

    return (
        <div
            className="modal-backdrop"
            onMouseDown={handleBackdropClick}
        >
            <div
                ref={cardRef}
                className={`modal-card modal-card--${size}`}
                role="dialog"
                aria-modal="true"
                aria-labelledby={titleId}
                aria-busy={closeDisabled}
            >
                <div className="modal-header">
                    <h2 id={titleId}>{title}</h2>

                    <button
                        type="button"
                        className="modal-close"
                        aria-label={
                            closeDisabled
                                ? 'Закрытие недоступно во время выполнения операции'
                                : 'Закрыть окно'
                        }
                        disabled={closeDisabled}
                        onClick={onClose}
                    >
                        ×
                    </button>
                </div>

                <div className="modal-body">
                    {children}
                </div>
            </div>
        </div>
    )
}

export default Modal

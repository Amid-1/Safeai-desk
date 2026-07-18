// ============================================================
// frontend/src/components/admin/Modal.tsx
// ============================================================

import { useEffect, useId, useRef } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'

type ModalProps = {
    title: string
    children: ReactNode
    onClose: () => void
    closeDisabled?: boolean
}

const FOCUSABLE_SELECTOR = [
    'a[href]',
    'button:not([disabled])',
    'textarea:not([disabled])',
    'input:not([disabled])',
    'select:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
].join(',')

function Modal({
                   title,
                   children,
                   onClose,
                   closeDisabled = false,
               }: ModalProps) {
    const titleId = useId()
    const modalRef = useRef<HTMLDivElement | null>(null)
    const previousActiveElementRef = useRef<Element | null>(null)

    useEffect(() => {
        previousActiveElementRef.current = document.activeElement

        const previousOverflow = document.body.style.overflow
        document.body.style.overflow = 'hidden'

        const focusableElements =
            modalRef.current?.querySelectorAll<HTMLElement>(
                FOCUSABLE_SELECTOR
            )

        const firstFocusableElement = focusableElements?.[0]

        if (firstFocusableElement) {
            firstFocusableElement.focus()
        } else {
            modalRef.current?.focus()
        }

        function handleKeyDown(event: globalThis.KeyboardEvent) {
            if (event.key === 'Escape' && !closeDisabled) {
                event.preventDefault()
                onClose()
            }
        }

        document.addEventListener('keydown', handleKeyDown)

        return () => {
            document.removeEventListener('keydown', handleKeyDown)
            document.body.style.overflow = previousOverflow

            if (
                previousActiveElementRef.current instanceof HTMLElement
                && document.contains(previousActiveElementRef.current)
            ) {
                previousActiveElementRef.current.focus()
            }
        }
    }, [closeDisabled, onClose])

    function handleBackdropMouseDown() {
        if (!closeDisabled) {
            onClose()
        }
    }

    function handleModalKeyDown(
        event: KeyboardEvent<HTMLDivElement>
    ) {
        if (event.key !== 'Tab') {
            return
        }

        const focusableElements = Array.from(
            modalRef.current?.querySelectorAll<HTMLElement>(
                FOCUSABLE_SELECTOR
            ) ?? []
        )

        if (focusableElements.length === 0) {
            event.preventDefault()
            modalRef.current?.focus()
            return
        }

        const firstElement = focusableElements[0]
        const lastElement =
            focusableElements[focusableElements.length - 1]

        if (
            event.shiftKey
            && (
                document.activeElement === firstElement
                || document.activeElement === modalRef.current
            )
        ) {
            event.preventDefault()
            lastElement.focus()
            return
        }

        if (
            !event.shiftKey
            && document.activeElement === lastElement
        ) {
            event.preventDefault()
            firstElement.focus()
        }
    }

    return (
        <div
            className="modal-backdrop"
            onMouseDown={handleBackdropMouseDown}
        >
            <div
                ref={modalRef}
                className="modal-card"
                role="dialog"
                aria-modal="true"
                aria-labelledby={titleId}
                aria-busy={closeDisabled}
                tabIndex={-1}
                onKeyDown={handleModalKeyDown}
                onMouseDown={(event) => event.stopPropagation()}
            >
                <div className="modal-header">
                    <h2 id={titleId}>{title}</h2>

                    <button
                        type="button"
                        className="secondary-button modal-close-button"
                        onClick={onClose}
                        aria-label="Закрыть окно"
                        disabled={closeDisabled}
                    >
                        ×
                    </button>
                </div>

                {children}
            </div>
        </div>
    )
}

export default Modal


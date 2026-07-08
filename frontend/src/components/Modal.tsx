// frontend/src/components/Modal.tsx
import { useEffect, useId, useRef } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'

type ModalProps = {
    title: string
    children: ReactNode
    onClose: () => void
}

const FOCUSABLE_SELECTOR = [
    'a[href]',
    'button:not([disabled])',
    'textarea:not([disabled])',
    'input:not([disabled])',
    'select:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
].join(',')

function Modal({ title, children, onClose }: ModalProps) {
    const titleId = useId()
    const modalRef = useRef<HTMLDivElement | null>(null)
    const previousActiveElementRef = useRef<Element | null>(null)

    useEffect(() => {
        previousActiveElementRef.current = document.activeElement

        const focusableElements = modalRef.current?.querySelectorAll<HTMLElement>(
            FOCUSABLE_SELECTOR
        )

        focusableElements?.[0]?.focus()

        function handleKeyDown(event: globalThis.KeyboardEvent) {
            if (event.key === 'Escape') {
                event.preventDefault()
                onClose()
            }
        }

        document.addEventListener('keydown', handleKeyDown)

        return () => {
            document.removeEventListener('keydown', handleKeyDown)

            if (previousActiveElementRef.current instanceof HTMLElement) {
                previousActiveElementRef.current.focus()
            }
        }
    }, [onClose])

    function handleModalKeyDown(event: KeyboardEvent<HTMLDivElement>) {
        if (event.key !== 'Tab') {
            return
        }

        const focusableElements = Array.from(
            modalRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) ?? []
        )

        if (focusableElements.length === 0) {
            event.preventDefault()
            return
        }

        const firstElement = focusableElements[0]
        const lastElement = focusableElements[focusableElements.length - 1]

        if (event.shiftKey && document.activeElement === firstElement) {
            event.preventDefault()
            lastElement.focus()
            return
        }

        if (!event.shiftKey && document.activeElement === lastElement) {
            event.preventDefault()
            firstElement.focus()
        }
    }

    return (
        <div className="modal-backdrop" onMouseDown={onClose}>
            <div
                ref={modalRef}
                className="modal-card"
                role="dialog"
                aria-modal="true"
                aria-labelledby={titleId}
                tabIndex={-1}
                onKeyDown={handleModalKeyDown}
                onMouseDown={(event) => event.stopPropagation()}
            >
                <div className="modal-header">
                    <h2 id={titleId}>{title}</h2>

                    <button
                        type="button"
                        className="secondary-button"
                        onClick={onClose}
                        aria-label="Close modal"
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
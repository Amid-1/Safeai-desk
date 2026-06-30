// frontend/src/components/Modal.tsx
import type { ReactNode } from 'react'

type ModalProps = {
    title: string
    children: ReactNode
    onClose: () => void
}

function Modal({ title, children, onClose }: ModalProps) {
    return (
        <div className="modal-backdrop" onMouseDown={onClose}>
            <div
                className="modal-card"
                role="dialog"
                aria-modal="true"
                aria-label={title}
                onMouseDown={(event) => event.stopPropagation()}
            >
                <div className="modal-header">
                    <h2>{title}</h2>

                    <button
                        type="button"
                        className="secondary-button"
                        onClick={onClose}
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
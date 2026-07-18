// ============================================================
// frontend/src/components/admin/ConfirmDialog.tsx
// ============================================================

import Modal from './Modal'

type ConfirmDialogProps = {
    title: string
    message: string
    confirmText?: string
    danger?: boolean
    loading?: boolean
    onConfirm: () => void
    onCancel: () => void
}

function ConfirmDialog({
                           title,
                           message,
                           confirmText = 'Подтвердить',
                           danger = false,
                           loading = false,
                           onConfirm,
                           onCancel,
                       }: ConfirmDialogProps) {
    return (
        <Modal
            title={title}
            onClose={onCancel}
            closeDisabled={loading}
            size="sm"
        >
            <p className="confirm-dialog__message">{message}</p>

            <div className="modal-actions">
                <button
                    type="button"
                    className="button button--secondary"
                    disabled={loading}
                    onClick={onCancel}
                >
                    Отмена
                </button>

                <button
                    type="button"
                    className={
                        danger
                            ? 'button button--danger'
                            : 'button button--primary'
                    }
                    disabled={loading}
                    onClick={onConfirm}
                >
                    {loading ? 'Выполняется...' : confirmText}
                </button>
            </div>
        </Modal>
    )
}

export default ConfirmDialog




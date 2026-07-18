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
        >
            <p>{message}</p>

            <div className="modal-actions">
                <button
                    type="button"
                    className="secondary-button"
                    disabled={loading}
                    onClick={onCancel}
                >
                    Отмена
                </button>

                <button
                    type="button"
                    className={danger ? 'danger-button' : undefined}
                    disabled={loading}
                    onClick={onConfirm}
                >
                    {loading ? 'Выполнение...' : confirmText}
                </button>
            </div>
        </Modal>
    )
}

export default ConfirmDialog


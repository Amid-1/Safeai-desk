// ============================================================
// frontend/src/components/admin/UserActionsMenu.tsx
// ============================================================
import { useEffect, useRef, useState } from 'react'

type UserActionsMenuProps = {
    disabled?: boolean
    canManage: boolean
    canDelete: boolean
    enabled: boolean
    onDetails: () => void
    onEdit: () => void
    onRoles: () => void
    onResetPassword: () => void
    onToggleEnabled: () => void
    onDelete: () => void
}

function UserActionsMenu({
                             disabled = false,
                             canManage,
                             canDelete,
                             enabled,
                             onDetails,
                             onEdit,
                             onRoles,
                             onResetPassword,
                             onToggleEnabled,
                             onDelete,
                         }: UserActionsMenuProps) {
    const [open, setOpen] = useState(false)
    const menuRef = useRef<HTMLDivElement>(null)

    useEffect(() => {
        if (!open) {
            return
        }

        function handleMouseDown(event: MouseEvent) {
            if (!menuRef.current?.contains(event.target as Node)) {
                setOpen(false)
            }
        }

        function handleKeyDown(event: KeyboardEvent) {
            if (event.key === 'Escape') {
                setOpen(false)
            }
        }

        document.addEventListener('mousedown', handleMouseDown)
        document.addEventListener('keydown', handleKeyDown)

        return () => {
            document.removeEventListener('mousedown', handleMouseDown)
            document.removeEventListener('keydown', handleKeyDown)
        }
    }, [open])

    function run(action: () => void) {
        setOpen(false)
        action()
    }

    return (
        <div className="user-actions">
            <button
                type="button"
                className="table-action-button"
                disabled={disabled}
                onClick={onDetails}
            >
                Подробнее
            </button>

            {canManage && (
                <button
                    type="button"
                    className="table-action-button"
                    disabled={disabled}
                    onClick={onEdit}
                >
                    Редактировать
                </button>
            )}

            {(canManage || canDelete) && (
                <div className="action-menu" ref={menuRef}>
                    <button
                        type="button"
                        className="action-menu__trigger"
                        aria-label="Дополнительные действия"
                        aria-haspopup="menu"
                        aria-expanded={open}
                        disabled={disabled}
                        onClick={() => setOpen((value) => !value)}
                    >
                        ⋮
                    </button>

                    {open && (
                        <div
                            className="action-menu__popup"
                            role="menu"
                        >
                            {canManage && (
                                <>
                                    <button
                                        type="button"
                                        role="menuitem"
                                        onClick={() => run(onRoles)}
                                    >
                                        Управление ролями
                                    </button>

                                    <button
                                        type="button"
                                        role="menuitem"
                                        onClick={() => run(onResetPassword)}
                                    >
                                        Установить новый пароль
                                    </button>

                                    <button
                                        type="button"
                                        role="menuitem"
                                        onClick={() => run(onToggleEnabled)}
                                    >
                                        {enabled
                                            ? 'Отключить пользователя'
                                            : 'Включить пользователя'}
                                    </button>
                                </>
                            )}

                            {canDelete && (
                                <button
                                    type="button"
                                    role="menuitem"
                                    className="action-menu__danger"
                                    onClick={() => run(onDelete)}
                                >
                                    Удалить навсегда
                                </button>
                            )}
                        </div>
                    )}
                </div>
            )}
        </div>
    )
}

export default UserActionsMenu
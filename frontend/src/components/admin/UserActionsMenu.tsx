// ============================================================
// frontend/src/components/admin/UserActionsMenu.tsx
// ============================================================
import {
    useEffect,
    useRef,
    useState,
} from 'react'

type UserActionsMenuProps = {
    disabled?: boolean
    canManage: boolean

    /**
     * Необязательный флаг для обратной совместимости.
     * При отсутствии сохраняется прежнее поведение:
     * управляемому пользователю доступно изменение роли.
     */
    canChangeRole?: boolean

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
    canChangeRole,
    canDelete,
    enabled,
    onDetails,
    onEdit,
    onRoles,
    onResetPassword,
    onToggleEnabled,
    onDelete,
}: UserActionsMenuProps) {
    const [open, setOpen] =
        useState(false)

    const menuRef =
        useRef<HTMLDivElement | null>(
            null,
        )

    const roleActionAvailable =
        canManage
        && (canChangeRole ?? true)

    const hasSecondaryActions =
        canManage || canDelete

    useEffect(() => {
        if (!open) {
            return
        }

        function handlePointerDown(
            event: PointerEvent,
        ) {
            if (
                !menuRef.current?.contains(
                    event.target as Node,
                )
            ) {
                setOpen(false)
            }
        }

        function handleKeyDown(
            event: KeyboardEvent,
        ) {
            if (event.key === 'Escape') {
                setOpen(false)
            }
        }

        document.addEventListener(
            'pointerdown',
            handlePointerDown,
        )
        document.addEventListener(
            'keydown',
            handleKeyDown,
        )

        return () => {
            document.removeEventListener(
                'pointerdown',
                handlePointerDown,
            )
            document.removeEventListener(
                'keydown',
                handleKeyDown,
            )
        }
    }, [open])

    useEffect(() => {
        if (
            disabled
            || !hasSecondaryActions
        ) {
            queueMicrotask(() => {
                setOpen(false)
            })
        }
    }, [
        disabled,
        hasSecondaryActions,
    ])

    function run(
        action: () => void,
    ) {
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

            {hasSecondaryActions && (
                <div
                    className="action-menu"
                    ref={menuRef}
                >
                    <button
                        type="button"
                        className="action-menu__trigger"
                        aria-label={
                            'Дополнительные действия'
                        }
                        aria-expanded={open}
                        aria-controls={
                            open
                                ? 'user-actions-popup'
                                : undefined
                        }
                        disabled={disabled}
                        onClick={() =>
                            setOpen(
                                (value) => !value,
                            )
                        }
                    >
                        ⋯
                    </button>

                    {open && (
                        <div
                            id="user-actions-popup"
                            className="action-menu__popup"
                        >
                            {roleActionAvailable && (
                                <button
                                    type="button"
                                    onClick={() =>
                                        run(onRoles)
                                    }
                                >
                                    Управление ролями
                                </button>
                            )}

                            {canManage && (
                                <>
                                    <button
                                        type="button"
                                        onClick={() =>
                                            run(
                                                onResetPassword,
                                            )
                                        }
                                    >
                                        Установить новый пароль
                                    </button>

                                    <button
                                        type="button"
                                        onClick={() =>
                                            run(
                                                onToggleEnabled,
                                            )
                                        }
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
                                    className={
                                        'action-menu__danger'
                                    }
                                    onClick={() =>
                                        run(onDelete)
                                    }
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

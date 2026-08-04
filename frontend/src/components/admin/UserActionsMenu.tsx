// ============================================================
// frontend/src/components/admin/UserActionsMenu.tsx
// ============================================================
import {
    useEffect,
    useId,
    useRef,
    useState,
} from 'react'

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
    const [open, setOpen] =
        useState(false)

    const containerRef =
        useRef<HTMLDivElement | null>(null)
    const triggerRef =
        useRef<HTMLButtonElement | null>(null)
    const popupId = useId()

    useEffect(() => {
        if (disabled) {
            setOpen(false)
        }
    }, [disabled])

    useEffect(() => {
        if (!open) {
            return
        }

        function handlePointerDown(
            event: PointerEvent,
        ) {
            if (
                !containerRef.current?.contains(
                    event.target as Node,
                )
            ) {
                closeAndReturnFocus(false)
            }
        }

        function handleKeyDown(
            event: KeyboardEvent,
        ) {
            if (event.key === 'Escape') {
                event.preventDefault()
                closeAndReturnFocus(true)
            }
        }

        function handleFocusIn(
            event: FocusEvent,
        ) {
            if (
                !containerRef.current?.contains(
                    event.target as Node,
                )
            ) {
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
        document.addEventListener(
            'focusin',
            handleFocusIn,
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
            document.removeEventListener(
                'focusin',
                handleFocusIn,
            )
        }
    }, [open])

    function closeAndReturnFocus(
        restoreFocus: boolean,
    ) {
        setOpen(false)

        if (restoreFocus) {
            window.requestAnimationFrame(
                () => {
                    triggerRef.current?.focus()
                },
            )
        }
    }

    function run(action: () => void) {
        if (disabled) {
            return
        }

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
                <div
                    ref={containerRef}
                    className="action-menu"
                >
                    <button
                        ref={triggerRef}
                        type="button"
                        className="action-menu__trigger"
                        aria-label={
                            'Дополнительные действия'
                        }
                        aria-haspopup="dialog"
                        aria-expanded={open}
                        aria-controls={
                            open
                                ? popupId
                                : undefined
                        }
                        disabled={disabled}
                        onClick={() =>
                            setOpen(
                                (value) => !value,
                            )
                        }
                    >
                        ⋮
                    </button>

                    {open && (
                        <div
                            id={popupId}
                            className={
                                'action-menu__popup'
                            }
                            aria-label={
                                'Дополнительные действия пользователя'
                            }
                        >
                            {canManage && (
                                <>
                                    <button
                                        type="button"
                                        disabled={
                                            disabled
                                        }
                                        onClick={() =>
                                            run(
                                                onRoles,
                                            )
                                        }
                                    >
                                        Управление ролями
                                    </button>

                                    <button
                                        type="button"
                                        disabled={
                                            disabled
                                        }
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
                                        disabled={
                                            disabled
                                        }
                                        onClick={() =>
                                            run(
                                                onToggleEnabled,
                                            )
                                        }
                                    >
                                        {enabled
                                            ? (
                                                'Отключить '
                                                + 'пользователя'
                                            )
                                            : (
                                                'Включить '
                                                + 'пользователя'
                                            )}
                                    </button>
                                </>
                            )}

                            {canDelete && (
                                <button
                                    type="button"
                                    className={
                                        'action-menu__danger'
                                    }
                                    disabled={disabled}
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

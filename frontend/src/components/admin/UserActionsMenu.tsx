// frontend/src/components/admin/UserActionsMenu.tsx
import {
    useEffect,
    useId,
    useRef,
    useState,
} from 'react'

type UserActionsMenuProps = {
    userLabel?: string
    disabled?: boolean
    canManage: boolean

    /**
     * Role mutation is intentionally separated from generic user management.
     * In the current backend contract only SUPER_ADMIN may switch USER/ADMIN.
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
    userLabel,
    disabled = false,
    canManage,
    canChangeRole = false,
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

    const triggerRef =
        useRef<HTMLButtonElement | null>(
            null,
        )

    const reactId =
        useId()

    const popupId =
        `user-actions-${reactId.replace(/:/g, '')}`

    const roleActionAvailable =
        canManage
        && canChangeRole

    const hasSecondaryActions =
        canManage
        || canDelete

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

                queueMicrotask(() => {
                    triggerRef.current?.focus()
                })
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

    const additionalActionsLabel =
        userLabel
            ? (
                'Дополнительные действия для '
                + userLabel
            )
            : 'Дополнительные действия'

    return (
        <div
            className="user-actions"
        >
            <button
                type="button"
                className={
                    'table-action-button'
                }
                disabled={disabled}
                onClick={onDetails}
            >
                Подробнее
            </button>

            {canManage && (
                <button
                    type="button"
                    className={
                        'table-action-button'
                    }
                    disabled={disabled}
                    title={
                        'Изменить email и полное имя'
                    }
                    onClick={onEdit}
                >
                    Email и имя
                </button>
            )}

            {hasSecondaryActions && (
                <div
                    className={
                        'action-menu'
                    }
                    ref={menuRef}
                >
                    <button
                        ref={triggerRef}
                        type="button"
                        className={
                            'action-menu__trigger'
                        }
                        aria-label={
                            additionalActionsLabel
                        }
                        aria-expanded={open}
                        aria-controls={
                            open
                                ? popupId
                                : undefined
                        }
                        disabled={disabled}
                        onClick={() =>
                            setOpen(
                                (value) =>
                                    !value,
                            )
                        }
                    >
                        ⋯
                    </button>

                    {open && (
                        <div
                            id={popupId}
                            className={
                                'action-menu__popup'
                            }
                        >
                            {roleActionAvailable
                                && (
                                    <button
                                        type="button"
                                        onClick={() =>
                                            run(
                                                onRoles,
                                            )
                                        }
                                    >
                                        Изменить роль
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
                                        run(
                                            onDelete,
                                        )
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

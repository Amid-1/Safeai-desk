import {
    useEffect,
    useId,
    useLayoutEffect,
    useRef,
    useState,
} from 'react'
import {createPortal} from 'react-dom'

type UserActionsMenuProps = {
    userLabel?: string
    disabled?: boolean
    canManage: boolean
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

type Placement = {top: number; left: number}
const POPUP_WIDTH = 250
const EDGE = 12
const GAP = 6

/** Actions render outside of scrollable tables, without bypassing role policy. */
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
    const [open, setOpen] = useState(false)
    const [placement, setPlacement] = useState<Placement | null>(null)
    const triggerRef = useRef<HTMLButtonElement | null>(null)
    const popupRef = useRef<HTMLDivElement | null>(null)
    const popupId = `user-actions-${useId().replace(/:/g, '')}`
    const hasSecondaryActions = canManage || canDelete
    const roleActionAvailable = canManage && canChangeRole

    useLayoutEffect(() => {
        if (!open) return
        const trigger = triggerRef.current
        const popup = popupRef.current
        if (!trigger || !popup) return

        function reposition() {
            const target = triggerRef.current
            const panel = popupRef.current
            if (!target || !panel) return
            const rect = target.getBoundingClientRect()
            const height = panel.getBoundingClientRect().height
            const width = Math.min(POPUP_WIDTH, window.innerWidth - EDGE * 2)
            const spaceBelow = window.innerHeight - rect.bottom - EDGE
            const showAbove = spaceBelow < height + GAP && rect.top > height + GAP + EDGE
            const top = showAbove
                ? Math.max(EDGE, rect.top - height - GAP)
                : Math.min(window.innerHeight - height - EDGE, rect.bottom + GAP)
            setPlacement({
                top: Math.max(EDGE, top),
                left: Math.max(EDGE, Math.min(rect.right - width, window.innerWidth - width - EDGE)),
            })
        }

        reposition()
        window.addEventListener('resize', reposition)
        // On scroll we close instead of leaving a detached menu by its row.
        const onScroll = () => setOpen(false)
        window.addEventListener('scroll', onScroll, true)
        return () => {
            window.removeEventListener('resize', reposition)
            window.removeEventListener('scroll', onScroll, true)
        }
    }, [open])

    useEffect(() => {
        if (!open) return
        const outside = (event: PointerEvent) => {
            const node = event.target as Node
            if (!triggerRef.current?.contains(node) && !popupRef.current?.contains(node)) {
                setOpen(false)
            }
        }
        const keyboard = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                event.preventDefault()
                setOpen(false)
                queueMicrotask(() => triggerRef.current?.focus())
            }
        }
        document.addEventListener('pointerdown', outside)
        document.addEventListener('keydown', keyboard)
        return () => {
            document.removeEventListener('pointerdown', outside)
            document.removeEventListener('keydown', keyboard)
        }
    }, [open])

    useEffect(() => {
        if (disabled || !hasSecondaryActions) {
            queueMicrotask(() => setOpen(false))
        }
    }, [disabled, hasSecondaryActions])

    const run = (action: () => void) => {
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
            {hasSecondaryActions && (
                <div className="action-menu">
                    <button
                        ref={triggerRef}
                        type="button"
                        className="action-menu__trigger"
                        aria-label={userLabel
                            ? `Дополнительные действия для ${userLabel}`
                            : 'Дополнительные действия'}
                        aria-expanded={open}
                        aria-haspopup="true"
                        aria-controls={open ? popupId : undefined}
                        disabled={disabled}
                        onClick={() => setOpen((value) => !value)}
                        onKeyDown={(event) => {
                            if (event.key === 'ArrowDown') {
                                event.preventDefault()
                                setOpen(true)
                                queueMicrotask(() => popupRef.current?.querySelector('button')?.focus())
                            }
                        }}
                    >
                        ⋯
                    </button>
                </div>
            )}
            {open && hasSecondaryActions && createPortal(
                <div
                    id={popupId}
                    ref={popupRef}
                    className="action-menu__popup action-menu__popup--portal"
                    style={{
                        top: placement?.top ?? 0,
                        left: placement?.left ?? 0,
                        width: `min(${POPUP_WIDTH}px, calc(100vw - ${EDGE * 2}px))`,
                        visibility: placement ? 'visible' : 'hidden',
                    }}
                >
                    {canManage && (
                        <button type="button" onClick={() => run(onEdit)}>
                            Изменить email и имя
                        </button>
                    )}
                    {roleActionAvailable && (
                        <button type="button" onClick={() => run(onRoles)}>
                            Изменить роль
                        </button>
                    )}
                    {canManage && (
                        <>
                            <button type="button" onClick={() => run(onResetPassword)}>
                                Установить новый пароль
                            </button>
                            <button type="button" onClick={() => run(onToggleEnabled)}>
                                {enabled ? 'Отключить пользователя' : 'Включить пользователя'}
                            </button>
                        </>
                    )}
                    {canDelete && (
                        <button type="button" className="action-menu__danger" onClick={() => run(onDelete)}>
                            Удалить навсегда
                        </button>
                    )}
                </div>,
                document.body,
            )}
        </div>
    )
}

export default UserActionsMenu

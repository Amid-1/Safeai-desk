import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

type Props = {
    organizationName: string
    enabled: boolean
    disabled: boolean
    onRename: () => void
    onToggleStatus: () => void
}

/** Kept outside the scrolling table so the action menu cannot be clipped. */
export function OrganizationActionsMenu({ organizationName, enabled, disabled, onRename, onToggleStatus }: Props) {
    const [open, setOpen] = useState(false)
    const [position, setPosition] = useState<{ top: number; left: number } | null>(null)
    const trigger = useRef<HTMLButtonElement>(null)
    const panel = useRef<HTMLDivElement>(null)
    const menuId = useId()

    useLayoutEffect(() => {
        if (!open) return
        const update = () => {
            const anchor = trigger.current?.getBoundingClientRect()
            const element = panel.current
            if (!anchor || !element) return
            const width = Math.min(230, window.innerWidth - 24)
            const height = element.getBoundingClientRect().height
            const above = anchor.bottom + height + 16 > window.innerHeight && anchor.top > height + 16
            setPosition({
                top: Math.max(12, above ? anchor.top - height - 5 : Math.min(anchor.bottom + 5, window.innerHeight - height - 12)),
                left: Math.max(12, Math.min(anchor.right - width, window.innerWidth - width - 12)),
            })
        }
        update()
        const closeOnScroll = () => setOpen(false)
        window.addEventListener('resize', update)
        window.addEventListener('scroll', closeOnScroll, true)
        return () => {
            window.removeEventListener('resize', update)
            window.removeEventListener('scroll', closeOnScroll, true)
        }
    }, [open])

    useEffect(() => {
        if (!open) return
        const outside = (event: PointerEvent) => {
            const target = event.target as Node
            if (!trigger.current?.contains(target) && !panel.current?.contains(target)) setOpen(false)
        }
        const escape = (event: KeyboardEvent) => {
            if (event.key === 'Escape') {
                event.preventDefault()
                setOpen(false)
                queueMicrotask(() => trigger.current?.focus())
            }
        }
        document.addEventListener('pointerdown', outside)
        document.addEventListener('keydown', escape)
        return () => {
            document.removeEventListener('pointerdown', outside)
            document.removeEventListener('keydown', escape)
        }
    }, [open])

    useEffect(() => { if (disabled) queueMicrotask(() => setOpen(false)) }, [disabled])

    const run = (callback: () => void) => { setOpen(false); callback() }
    return <>
        <button type="button" className="action-menu__trigger organization-actions-trigger"
            ref={trigger} disabled={disabled} aria-label={`Действия для организации ${organizationName}`}
            aria-expanded={open} aria-haspopup="true" aria-controls={open ? menuId : undefined}
            onClick={() => setOpen(value => !value)}
            onKeyDown={event => {
                if (event.key === 'ArrowDown') {
                    event.preventDefault()
                    setOpen(true)
                    queueMicrotask(() => panel.current?.querySelector('button')?.focus())
                }
            }}>⋯</button>
        {open && createPortal(<div id={menuId} ref={panel}
            className="action-menu__popup action-menu__popup--portal organization-actions-popup"
            style={{ position: 'fixed', top: position?.top ?? 0, left: position?.left ?? 0,
                width: 'min(230px, calc(100vw - 24px))', visibility: position ? 'visible' : 'hidden' }}>
            <button type="button" onClick={() => run(onRename)}>Переименовать</button>
            <button type="button" className={enabled ? 'action-menu__danger action-menu__security-start' : 'action-menu__security-start'}
                onClick={() => run(onToggleStatus)}>{enabled ? 'Отключить организацию' : 'Включить организацию'}</button>
        </div>, document.body)}
    </>
}

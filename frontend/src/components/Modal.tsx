// ============================================================
// frontend/src/components/Modal.tsx
// ============================================================
import {
    useId,
    useLayoutEffect,
    useRef,
    useState,
    useSyncExternalStore,
} from 'react'
import type {
    PointerEvent as ReactPointerEvent,
    ReactNode,
    RefObject,
} from 'react'
import {
    createPortal,
} from 'react-dom'

type ModalProps = {
    title: string
    children: ReactNode
    onClose: () => void

    closeDisabled?: boolean
    size?: 'sm' | 'md' | 'lg'

    descriptionId?: string
    initialFocusRef?:
        RefObject<HTMLElement | null>
}

type ModalEntry = {
    id: symbol
    card: HTMLElement
}

const FOCUSABLE_SELECTOR = [
    'button:not([disabled])',
    'input:not([disabled])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    'a[href]',
    '[contenteditable="true"]',
    '[tabindex]:not([tabindex="-1"])',
].join(',')

let modalEntries: ModalEntry[] = []
let modalStackVersion = 0

const modalStackListeners =
    new Set<() => void>()

let rootPreviouslyInert = false
let rootPreviousAriaHidden:
    string | null = null

let bodyPreviouslyLocked = false

function Modal({
    title,
    children,
    onClose,
    closeDisabled = false,
    size = 'md',
    descriptionId,
    initialFocusRef,
}: ModalProps) {
    const titleId = useId()

    const cardRef =
        useRef<HTMLDivElement | null>(
            null,
        )

    const backdropRef =
        useRef<HTMLDivElement | null>(
            null,
        )

    const previousActiveElementRef =
        useRef<HTMLElement | null>(
            null,
        )

    const [modalId] = useState(
        () => Symbol('safeai-modal'),
    )

    const onCloseRef = useRef(onClose)
    const closeDisabledRef =
        useRef(closeDisabled)
    const initialFocusRefRef =
        useRef(initialFocusRef)

    useLayoutEffect(() => {
        onCloseRef.current = onClose
        closeDisabledRef.current =
            closeDisabled
        initialFocusRefRef.current =
            initialFocusRef
    }, [
        onClose,
        closeDisabled,
        initialFocusRef,
    ])

    const [portalRoot] =
        useState<HTMLElement>(
            getOrCreateModalRoot,
        )

    useSyncExternalStore(
        subscribeModalStack,
        getModalStackSnapshot,
        getModalStackSnapshot,
    )

    const registered =
        isRegisteredModal(modalId)

    const topmost =
        !registered
        || isTopmostModal(modalId)

    useLayoutEffect(() => {
        const backdrop =
            backdropRef.current

        if (!backdrop) {
            return
        }

        if (topmost) {
            backdrop.removeAttribute(
                'inert',
            )
            backdrop.removeAttribute(
                'aria-hidden',
            )
        } else {
            backdrop.setAttribute(
                'inert',
                '',
            )
            backdrop.setAttribute(
                'aria-hidden',
                'true',
            )
        }
    }, [topmost])

    useLayoutEffect(() => {
        const card = cardRef.current

        if (!card) {
            return
        }

        previousActiveElementRef.current =
            document.activeElement
                instanceof HTMLElement
                ? document.activeElement
                : null

        const focusTarget =
            resolveInitialFocus(
                card,
                initialFocusRefRef.current,
            )

        // Сначала переводим focus в portal,
        // затем делаем #root inert/aria-hidden.
        // Это исключает browser warning о попытке
        // скрыть ancestor текущего focused trigger.
        focusTarget.focus({
            preventScroll: true,
        })

        registerModal(
            modalId,
            card,
        )

        function handleKeyDown(
            event: KeyboardEvent,
        ) {
            if (
                !isTopmostModal(
                    modalId,
                )
            ) {
                return
            }

            const currentCard =
                cardRef.current

            if (!currentCard) {
                return
            }

            if (event.key === 'Escape') {
                if (
                    !closeDisabledRef.current
                ) {
                    event.preventDefault()
                    event.stopPropagation()
                    onCloseRef.current()
                }

                return
            }

            if (event.key !== 'Tab') {
                return
            }

            const focusable =
                getFocusableElements(
                    currentCard,
                )

            if (focusable.length === 0) {
                event.preventDefault()
                currentCard.focus({
                    preventScroll: true,
                })
                return
            }

            const first = focusable[0]
            const last =
                focusable[
                    focusable.length - 1
                ]

            if (!first || !last) {
                return
            }

            const active =
                document.activeElement

            if (
                !currentCard.contains(active)
            ) {
                event.preventDefault()
                first.focus({
                    preventScroll: true,
                })
                return
            }

            if (
                event.shiftKey
                && active === first
            ) {
                event.preventDefault()
                last.focus({
                    preventScroll: true,
                })
                return
            }

            if (
                !event.shiftKey
                && active === last
            ) {
                event.preventDefault()
                first.focus({
                    preventScroll: true,
                })
            }
        }

        document.addEventListener(
            'keydown',
            handleKeyDown,
            true,
        )

        return () => {
            document.removeEventListener(
                'keydown',
                handleKeyDown,
                true,
            )

            unregisterModal(modalId)

            restoreFocusAfterModalClose(
                previousActiveElementRef.current,
            )
        }
    }, [modalId])

    function handleBackdropPointerDown(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        if (
            event.target
                === event.currentTarget
            && isTopmostModal(modalId)
            && !closeDisabledRef.current
        ) {
            onCloseRef.current()
        }
    }

    return createPortal(
        <div
            ref={backdropRef}
            className="modal-backdrop"
            onPointerDown={
                handleBackdropPointerDown
            }
        >
            <div
                ref={cardRef}
                className={
                    `modal-card modal-card--${size}`
                }
                role="dialog"
                aria-modal="true"
                aria-labelledby={titleId}
                aria-describedby={
                    descriptionId
                }
                aria-busy={
                    closeDisabled
                }
                tabIndex={-1}
            >
                <div className="modal-header">
                    <h2 id={titleId}>
                        {title}
                    </h2>

                    <button
                        type="button"
                        className={
                            'modal-close-button'
                        }
                        data-modal-close="true"
                        aria-label={
                            closeDisabled
                                ? (
                                    'Закрытие недоступно '
                                    + 'во время выполнения операции'
                                )
                                : 'Закрыть окно'
                        }
                        disabled={
                            closeDisabled
                        }
                        onClick={() => {
                            if (
                                !closeDisabledRef
                                    .current
                            ) {
                                onCloseRef.current()
                            }
                        }}
                    >
                        ×
                    </button>
                </div>

                <div className="modal-body">
                    {children}
                </div>
            </div>
        </div>,
        portalRoot,
    )
}

function resolveInitialFocus(
    card: HTMLElement,
    initialFocusRef:
        RefObject<HTMLElement | null>
        | undefined,
): HTMLElement {
    const requested =
        initialFocusRef?.current

    if (
        requested
        && card.contains(requested)
        && isFocusable(requested)
    ) {
        return requested
    }

    const focusable =
        getFocusableElements(card)

    const nonCloseControl =
        focusable.find(
            (element) =>
                !element.hasAttribute(
                    'data-modal-close',
                ),
        )

    return nonCloseControl
        ?? focusable[0]
        ?? card
}

function getFocusableElements(
    container: HTMLElement,
): HTMLElement[] {
    return Array.from(
        container
            .querySelectorAll<HTMLElement>(
                FOCUSABLE_SELECTOR,
            ),
    ).filter(isFocusable)
}

function isFocusable(
    element: HTMLElement,
): boolean {
    if (
        element.hidden
        || element.getAttribute(
            'aria-hidden',
        ) === 'true'
        || element.closest('[inert]')
        || (
            'disabled' in element
            && Boolean(
                (element as HTMLButtonElement)
                    .disabled,
            )
        )
    ) {
        return false
    }

    const style =
        window.getComputedStyle(element)

    return style.display !== 'none'
        && style.visibility !== 'hidden'
}

function registerModal(
    id: symbol,
    card: HTMLElement,
) {
    const wasEmpty =
        modalEntries.length === 0

    modalEntries = [
        ...modalEntries.filter(
            (entry) => entry.id !== id,
        ),
        {
            id,
            card,
        },
    ]

    if (wasEmpty) {
        lockApplicationBackground()
    }

    publishModalStackChange()
}

function unregisterModal(
    id: symbol,
) {
    modalEntries =
        modalEntries.filter(
            (entry) => entry.id !== id,
        )

    if (modalEntries.length === 0) {
        unlockApplicationBackground()
    }

    publishModalStackChange()
}

function isRegisteredModal(
    id: symbol,
): boolean {
    return modalEntries.some(
        (entry) => entry.id === id,
    )
}

function isTopmostModal(
    id: symbol,
): boolean {
    return modalEntries[
        modalEntries.length - 1
    ]?.id === id
}

function getTopmostModal():
    ModalEntry | null {
    return modalEntries[
        modalEntries.length - 1
    ] ?? null
}

function subscribeModalStack(
    listener: () => void,
): () => void {
    modalStackListeners.add(listener)

    return () => {
        modalStackListeners.delete(
            listener,
        )
    }
}

function getModalStackSnapshot():
    number {
    return modalStackVersion
}

function publishModalStackChange() {
    modalStackVersion += 1

    modalStackListeners.forEach(
        (listener) => listener(),
    )
}

function lockApplicationBackground() {
    const applicationRoot =
        document.getElementById('root')

    if (applicationRoot) {
        rootPreviouslyInert =
            applicationRoot.hasAttribute(
                'inert',
            )

        rootPreviousAriaHidden =
            applicationRoot.getAttribute(
                'aria-hidden',
            )

        applicationRoot.setAttribute(
            'inert',
            '',
        )
        applicationRoot.setAttribute(
            'aria-hidden',
            'true',
        )
    }

    bodyPreviouslyLocked =
        document.body.classList.contains(
            'modal-open',
        )

    document.body.classList.add(
        'modal-open',
    )
}

function unlockApplicationBackground() {
    const applicationRoot =
        document.getElementById('root')

    if (applicationRoot) {
        if (!rootPreviouslyInert) {
            applicationRoot.removeAttribute(
                'inert',
            )
        }

        if (
            rootPreviousAriaHidden
                === null
        ) {
            applicationRoot.removeAttribute(
                'aria-hidden',
            )
        } else {
            applicationRoot.setAttribute(
                'aria-hidden',
                rootPreviousAriaHidden,
            )
        }
    }

    if (!bodyPreviouslyLocked) {
        document.body.classList.remove(
            'modal-open',
        )
    }
}

function restoreFocusAfterModalClose(
    previous: HTMLElement | null,
) {
    window.requestAnimationFrame(() => {
        const topmost =
            getTopmostModal()

        if (topmost) {
            if (
                previous?.isConnected
                && topmost.card.contains(
                    previous,
                )
            ) {
                previous.focus({
                    preventScroll: true,
                })
                return
            }

            resolveInitialFocus(
                topmost.card,
                undefined,
            ).focus({
                preventScroll: true,
            })
            return
        }

        if (previous?.isConnected) {
            previous.focus({
                preventScroll: true,
            })
        }
    })
}

function getOrCreateModalRoot():
    HTMLElement {
    const existing =
        document.getElementById(
            'modal-root',
        )

    if (existing) {
        return existing
    }

    const created =
        document.createElement('div')

    created.id = 'modal-root'
    document.body.append(created)

    return created
}

export default Modal

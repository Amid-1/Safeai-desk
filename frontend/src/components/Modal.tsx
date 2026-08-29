// ============================================================
// frontend/src/components/Modal.tsx
// ============================================================
import {
    useId,
    useLayoutEffect,
    useMemo,
    useRef,
    useState,
    useSyncExternalStore,
} from 'react'
import type {
    CSSProperties,
    PointerEvent as ReactPointerEvent,
    ReactNode,
    RefObject,
} from 'react'
import {
    createPortal,
} from 'react-dom'

export type ModalResizeOptions = {
    initialWidth: number
    initialHeight: number
    minWidth: number
    minHeight: number
    maxWidth?: number
    maxHeight?: number
    scaleContent?: boolean
}

type ModalProps = {
    title: string
    children: ReactNode
    onClose: () => void

    closeDisabled?: boolean
    size?: 'sm' | 'md' | 'lg'
    className?: string
    resize?: ModalResizeOptions

    descriptionId?: string
    initialFocusRef?:
        RefObject<HTMLElement | null>
}

type ModalBounds = {
    left: number
    top: number
    width: number
    height: number
}

type ResizeDirection =
    | 'n'
    | 'ne'
    | 'e'
    | 'se'
    | 's'
    | 'sw'
    | 'w'
    | 'nw'

type ResizeSession = {
    pointerId: number
    direction: ResizeDirection
    startX: number
    startY: number
    startBounds: ModalBounds
}

type ResolvedModalResize =
    | {
        enabled: false
    }
    | {
        enabled: true
        options: ModalResizeOptions
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

const RESIZE_DIRECTIONS: readonly ResizeDirection[] = [
    'n',
    'ne',
    'e',
    'se',
    's',
    'sw',
    'w',
    'nw',
]

const RESIZE_VIEWPORT_MARGIN = 12

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
    className = '',
    resize,
    descriptionId,
    initialFocusRef,
}: ModalProps) {
    const resizeInitialWidth =
        resize?.initialWidth
    const resizeInitialHeight =
        resize?.initialHeight
    const resizeMinWidth =
        resize?.minWidth
    const resizeMinHeight =
        resize?.minHeight
    const resizeMaxWidth =
        resize?.maxWidth
    const resizeMaxHeight =
        resize?.maxHeight
    const resizeScaleContent =
        resize?.scaleContent

    const resizeState = useMemo<
        ResolvedModalResize
    >(
        () => {
            if (
                resizeInitialWidth
                    == null
                || resizeInitialHeight
                    == null
                || resizeMinWidth
                    == null
                || resizeMinHeight
                    == null
            ) {
                return {
                    enabled: false,
                }
            }

            return {
                enabled: true,
                options: {
                    initialWidth:
                        resizeInitialWidth,
                    initialHeight:
                        resizeInitialHeight,
                    minWidth:
                        resizeMinWidth,
                    minHeight:
                        resizeMinHeight,
                    maxWidth:
                        resizeMaxWidth,
                    maxHeight:
                        resizeMaxHeight,
                    scaleContent:
                        resizeScaleContent,
                },
            }
        },
        [
            resizeInitialWidth,
            resizeInitialHeight,
            resizeMinWidth,
            resizeMinHeight,
            resizeMaxWidth,
            resizeMaxHeight,
            resizeScaleContent,
        ],
    )

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

    const resizeSessionRef =
        useRef<ResizeSession | null>(
            null,
        )

    const [resizeBounds, setResizeBounds] =
        useState<ModalBounds | null>(
            () => resizeState.enabled
                ? createInitialModalBounds(
                    resizeState.options,
                )
                : null,
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

    useLayoutEffect(() => {
        if (!resizeState.enabled) {
            setResizeBounds(null)
            return
        }

        const options =
            resizeState.options

        const fitBounds = (
            current: ModalBounds | null,
        ): ModalBounds => (
            current
                ? fitModalBoundsToViewport(
                    current,
                    options,
                )
                : createInitialModalBounds(
                    options,
                )
        )

        setResizeBounds(fitBounds)

        function handleWindowResize() {
            setResizeBounds(fitBounds)
        }

        window.addEventListener(
            'resize',
            handleWindowResize,
        )

        return () => {
            window.removeEventListener(
                'resize',
                handleWindowResize,
            )
        }
    }, [resizeState])

    useLayoutEffect(() => (
        () => {
            resizeSessionRef.current = null
            document.body.classList.remove(
                'modal-resizing',
            )
        }
    ), [])

    const resizeScale =
        resizeState.enabled
        && resizeState.options.scaleContent
        && resizeBounds
            ? calculateResizeScale(
                resizeBounds,
                resizeState.options,
            )
            : 1

    const cardStyle =
        resizeBounds
            ? {
                position: 'fixed',
                left: resizeBounds.left,
                top: resizeBounds.top,
                width: resizeBounds.width,
                height: resizeBounds.height,
                maxWidth: 'none',
                maxHeight: 'none',
                '--modal-resize-scale':
                    String(resizeScale),
                '--modal-resize-font-size':
                    `${13 * resizeScale}px`,
                '--modal-resize-hint-font-size':
                    `${12 * resizeScale}px`,
                '--modal-resize-title-font-size':
                    `${22 * resizeScale}px`,
                '--modal-resize-control-font-size':
                    `${12 * resizeScale}px`,
                '--modal-resize-control-height':
                    `${38 * resizeScale}px`,
                '--modal-resize-textarea-height':
                    `${76 * resizeScale}px`,
                '--modal-resize-gap':
                    `${10 * resizeScale}px`,
                '--modal-resize-padding-x':
                    `${20 * resizeScale}px`,
                '--modal-resize-header-padding-y':
                    `${14 * resizeScale}px`,
                '--modal-resize-body-padding-y':
                    `${13 * resizeScale}px`,
                '--modal-resize-checkbox-size':
                    `${14 * resizeScale}px`,
            } as CSSProperties
            : undefined

    function handleResizePointerDown(
        direction: ResizeDirection,
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        if (
            !resizeState.enabled
            || !resizeBounds
            || !topmost
        ) {
            return
        }

        event.preventDefault()
        event.stopPropagation()

        const handle =
            event.currentTarget

        if (
            typeof handle.setPointerCapture
                === 'function'
        ) {
            handle.setPointerCapture(
                event.pointerId,
            )
        }

        resizeSessionRef.current = {
            pointerId: event.pointerId,
            direction,
            startX: event.clientX,
            startY: event.clientY,
            startBounds: resizeBounds,
        }

        document.body.classList.add(
            'modal-resizing',
        )
    }

    function handleResizePointerMove(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        const session =
            resizeSessionRef.current

        if (
            !resizeState.enabled
            || !session
            || session.pointerId
                !== event.pointerId
        ) {
            return
        }

        event.preventDefault()

        setResizeBounds(
            resizeModalBounds(
                session,
                event.clientX,
                event.clientY,
                resizeState.options,
            ),
        )
    }

    function handleResizePointerEnd(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        const session =
            resizeSessionRef.current

        if (
            !session
            || session.pointerId
                !== event.pointerId
        ) {
            return
        }

        const handle =
            event.currentTarget

        if (
            typeof handle.hasPointerCapture
                === 'function'
            && handle.hasPointerCapture(
                event.pointerId,
            )
            && typeof handle.releasePointerCapture
                === 'function'
        ) {
            handle.releasePointerCapture(
                event.pointerId,
            )
        }

        resizeSessionRef.current = null

        document.body.classList.remove(
            'modal-resizing',
        )
    }

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
                className={[
                    'modal-card',
                    `modal-card--${size}`,
                    resizeState.enabled
                        ? 'modal-card--resizable'
                        : '',
                    className,
                ]
                    .filter(Boolean)
                    .join(' ')}
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
                style={cardStyle}
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

                {resizeState.enabled && resizeBounds && (
                    <div
                        className="modal-resize-layer"
                        aria-hidden="true"
                    >
                        {RESIZE_DIRECTIONS.map(
                            (direction) => (
                                <div
                                    key={direction}
                                    className={[
                                        'modal-resize-handle',
                                        `modal-resize-handle--${direction}`,
                                    ].join(' ')}
                                    data-modal-resize-handle={
                                        direction
                                    }
                                    onPointerDown={(event) => {
                                        handleResizePointerDown(
                                            direction,
                                            event,
                                        )
                                    }}
                                    onPointerMove={
                                        handleResizePointerMove
                                    }
                                    onPointerUp={
                                        handleResizePointerEnd
                                    }
                                    onPointerCancel={
                                        handleResizePointerEnd
                                    }
                                />
                            ),
                        )}
                    </div>
                )}
            </div>
        </div>,
        portalRoot,
    )
}

function clamp(
    value: number,
    minimum: number,
    maximum: number,
): number {
    return Math.min(
        Math.max(value, minimum),
        maximum,
    )
}

function getResizeLimits(
    resize: ModalResizeOptions,
) {
    const viewportWidth =
        Math.max(
            320,
            window.innerWidth,
        )
    const viewportHeight =
        Math.max(
            320,
            window.innerHeight,
        )

    const availableWidth =
        Math.max(
            240,
            viewportWidth
                - RESIZE_VIEWPORT_MARGIN * 2,
        )
    const availableHeight =
        Math.max(
            240,
            viewportHeight
                - RESIZE_VIEWPORT_MARGIN * 2,
        )

    const minWidth =
        Math.min(
            resize.minWidth,
            availableWidth,
        )
    const minHeight =
        Math.min(
            resize.minHeight,
            availableHeight,
        )

    const maxWidth =
        Math.max(
            minWidth,
            Math.min(
                resize.maxWidth
                    ?? availableWidth,
                availableWidth,
            ),
        )
    const maxHeight =
        Math.max(
            minHeight,
            Math.min(
                resize.maxHeight
                    ?? availableHeight,
                availableHeight,
            ),
        )

    return {
        viewportWidth,
        viewportHeight,
        minWidth,
        minHeight,
        maxWidth,
        maxHeight,
    }
}

function createInitialModalBounds(
    resize: ModalResizeOptions,
): ModalBounds {
    const limits =
        getResizeLimits(resize)

    const width = clamp(
        resize.initialWidth,
        limits.minWidth,
        limits.maxWidth,
    )
    const height = clamp(
        resize.initialHeight,
        limits.minHeight,
        limits.maxHeight,
    )

    return {
        left:
            (limits.viewportWidth - width)
            / 2,
        top:
            (limits.viewportHeight - height)
            / 2,
        width,
        height,
    }
}

function fitModalBoundsToViewport(
    bounds: ModalBounds,
    resize: ModalResizeOptions,
): ModalBounds {
    const limits =
        getResizeLimits(resize)

    const width = clamp(
        bounds.width,
        limits.minWidth,
        limits.maxWidth,
    )
    const height = clamp(
        bounds.height,
        limits.minHeight,
        limits.maxHeight,
    )

    const maxLeft =
        Math.max(
            RESIZE_VIEWPORT_MARGIN,
            limits.viewportWidth
                - RESIZE_VIEWPORT_MARGIN
                - width,
        )
    const maxTop =
        Math.max(
            RESIZE_VIEWPORT_MARGIN,
            limits.viewportHeight
                - RESIZE_VIEWPORT_MARGIN
                - height,
        )

    return {
        left: clamp(
            bounds.left,
            RESIZE_VIEWPORT_MARGIN,
            maxLeft,
        ),
        top: clamp(
            bounds.top,
            RESIZE_VIEWPORT_MARGIN,
            maxTop,
        ),
        width,
        height,
    }
}

function resizeModalBounds(
    session: ResizeSession,
    clientX: number,
    clientY: number,
    resize: ModalResizeOptions,
): ModalBounds {
    const {
        direction,
        startX,
        startY,
        startBounds,
    } = session

    const limits =
        getResizeLimits(resize)

    const deltaX =
        clientX - startX
    const deltaY =
        clientY - startY

    const startLeft =
        startBounds.left
    const startTop =
        startBounds.top
    const startRight =
        startBounds.left
        + startBounds.width
    const startBottom =
        startBounds.top
        + startBounds.height

    let left = startLeft
    let right = startRight
    let top = startTop
    let bottom = startBottom

    if (direction.includes('e')) {
        right = clamp(
            startRight + deltaX,
            startLeft + limits.minWidth,
            Math.min(
                limits.viewportWidth
                    - RESIZE_VIEWPORT_MARGIN,
                startLeft
                    + limits.maxWidth,
            ),
        )
    }

    if (direction.includes('w')) {
        left = clamp(
            startLeft + deltaX,
            Math.max(
                RESIZE_VIEWPORT_MARGIN,
                startRight
                    - limits.maxWidth,
            ),
            startRight
                - limits.minWidth,
        )
    }

    if (direction.includes('s')) {
        bottom = clamp(
            startBottom + deltaY,
            startTop + limits.minHeight,
            Math.min(
                limits.viewportHeight
                    - RESIZE_VIEWPORT_MARGIN,
                startTop
                    + limits.maxHeight,
            ),
        )
    }

    if (direction.includes('n')) {
        top = clamp(
            startTop + deltaY,
            Math.max(
                RESIZE_VIEWPORT_MARGIN,
                startBottom
                    - limits.maxHeight,
            ),
            startBottom
                - limits.minHeight,
        )
    }

    return {
        left,
        top,
        width: right - left,
        height: bottom - top,
    }
}

function calculateResizeScale(
    bounds: ModalBounds,
    resize: ModalResizeOptions,
): number {
    const widthRatio =
        bounds.width
        / resize.initialWidth
    const heightRatio =
        bounds.height
        / resize.initialHeight

    return clamp(
        Math.sqrt(
            widthRatio * heightRatio,
        ),
        0.88,
        1.22,
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

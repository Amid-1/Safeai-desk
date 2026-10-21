// frontend/src/shared/ui/modal/useModalResize.ts
// Ответственность модуля: перетаскивание, изменение размеров и разворачивание окна.

import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties, PointerEvent as ReactPointerEvent } from 'react'
import {
    calculateResizeScale,
    clamp,
    createInitialModalBounds,
    createMaximizedModalBounds,
    fitModalBoundsToViewport,
    getResizeLimits,
    resizeModalBounds,
    RESIZE_VIEWPORT_MARGIN,
} from './modalGeometry'
import type {
    DragSession,
    ModalBounds,
    ModalResizeOptions,
    ResolvedModalResize,
    ResizeDirection,
    ResizeSession,
} from './modalGeometry'

export function useModalResize(resize: ModalResizeOptions | undefined, topmost: boolean) {
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
    const resizeMinScale =
        resize?.minScale
    const resizeMaxScale =
        resize?.maxScale
    const resizeMaximizable =
        resize?.maximizable

    const resizeState =
        useMemo<ResolvedModalResize>(
            () => {
                if (
                    resizeInitialWidth == null
                    || resizeInitialHeight == null
                    || resizeMinWidth == null
                    || resizeMinHeight == null
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
                        minScale:
                            resizeMinScale,
                        maxScale:
                            resizeMaxScale,
                        maximizable:
                            resizeMaximizable,
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
                resizeMinScale,
                resizeMaxScale,
                resizeMaximizable,
            ],
        )

    const resizeSessionRef =
        useRef<ResizeSession | null>(
            null,
        )

    const dragSessionRef =
        useRef<DragSession | null>(
            null,
        )

    const restoreBoundsRef =
        useRef<ModalBounds | null>(
            null,
        )

    const [maximized, setMaximized] =
        useState(false)

    const [resizeBounds, setResizeBounds] =
        useState<ModalBounds | null>(
            () =>
                resizeState.enabled
                    ? createInitialModalBounds(
                        resizeState.options,
                    )
                    : null,
        )

    useLayoutEffect(() => {
        if (
            !resizeState.enabled
        ) {
            setResizeBounds(
                null,
            )

            return
        }

        const options =
            resizeState.options

        const fitBounds = (
            current:
                ModalBounds | null,
        ): ModalBounds => (
            maximized
                ? createMaximizedModalBounds()
                : current
                ? fitModalBoundsToViewport(
                    current,
                    options,
                )
                : createInitialModalBounds(
                    options,
                )
        )

        setResizeBounds(
            fitBounds,
        )

        function handleWindowResize() {
            setResizeBounds(
                fitBounds,
            )
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
    }, [
        maximized,
        resizeState,
    ])

    useLayoutEffect(
        () => (
            () => {
                resizeSessionRef.current =
                    null

                dragSessionRef.current =
                    null

                document.body.classList.remove(
                    'modal-resizing',
                )

                document.body.classList.remove(
                    'modal-dragging',
                )
            }
        ),
        [],
    )

    const resizeScale =
        resizeState.enabled
        && resizeState.options
            .scaleContent
        && resizeBounds
            ? calculateResizeScale(
                resizeBounds,
                resizeState.options,
            )
            : 1

    const cardStyle =
        resizeBounds
            ? {
                position:
                    'fixed',

                left:
                    resizeBounds.left,

                top:
                    resizeBounds.top,

                width:
                    resizeBounds.width,

                height:
                    resizeBounds.height,

                maxWidth:
                    'none',

                maxHeight:
                    'none',

                '--modal-resize-scale':
                    String(
                        resizeScale,
                    ),

                '--modal-resize-font-size':
                    `${13 * resizeScale}px`,

                '--modal-resize-hint-font-size':
                    `${12 * resizeScale}px`,

                '--modal-resize-small-font-size':
                    `${11 * resizeScale}px`,

                '--modal-resize-title-font-size':
                    `${22 * resizeScale}px`,

                '--modal-resize-strong-font-size':
                    `${15 * resizeScale}px`,

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

                '--modal-resize-footer-padding-y':
                    `${10 * resizeScale}px`,
            } as CSSProperties
            : undefined

    function toggleMaximized() {
        if (
            !resizeState.enabled
            || !resizeState.options.maximizable
            || !resizeBounds
        ) {
            return
        }

        resizeSessionRef.current =
            null

        dragSessionRef.current =
            null

        document.body.classList.remove(
            'modal-resizing',
        )

        document.body.classList.remove(
            'modal-dragging',
        )

        if (maximized) {
            const restored =
                restoreBoundsRef.current
                ?? createInitialModalBounds(
                    resizeState.options,
                )

            setMaximized(false)
            setResizeBounds(
                fitModalBoundsToViewport(
                    restored,
                    resizeState.options,
                ),
            )
            return
        }

        restoreBoundsRef.current =
            resizeBounds

        setMaximized(true)
        setResizeBounds(
            createMaximizedModalBounds(),
        )
    }

    function handleResizePointerDown(
        direction:
            ResizeDirection,
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        if (
            !resizeState.enabled
            || !resizeBounds
            || !topmost
            || maximized
        ) {
            return
        }

        event.preventDefault()
        event.stopPropagation()

        const handle =
            event.currentTarget

        if (
            typeof handle
                .setPointerCapture
                === 'function'
        ) {
            try {
                handle.setPointerCapture(
                    event.pointerId,
                )
            } catch {
                // Захват указателя необязателен для работы.
            }
        }

        resizeSessionRef.current = {
            pointerId:
                event.pointerId,

            direction,

            startX:
                event.clientX,

            startY:
                event.clientY,

            startBounds:
                resizeBounds,
        }

        document.body.classList.add(
            'modal-resizing',
        )

        dragSessionRef.current =
            null
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
            typeof handle
                .hasPointerCapture
                === 'function'
            && handle.hasPointerCapture(
                event.pointerId,
            )
            && typeof handle
                .releasePointerCapture
                === 'function'
        ) {
            try {
                handle.releasePointerCapture(
                    event.pointerId,
                )
            } catch {
                // Браузер мог освободить указатель раньше.
            }
        }

        resizeSessionRef.current =
            null

        document.body.classList.remove(
            'modal-resizing',
        )
    }

    function handleDragPointerDown(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        if (
            !resizeState.enabled
            || !resizeBounds
            || !topmost
            || maximized
            || event.button !== 0
        ) {
            return
        }

        const eventTarget =
            event.target

        if (
            eventTarget instanceof Element
            && eventTarget.closest(
                'button, a, input, select, textarea, [role="button"]',
            )
        ) {
            return
        }

        event.preventDefault()

        const handle =
            event.currentTarget

        if (
            typeof handle
                .setPointerCapture
                === 'function'
        ) {
            try {
                handle.setPointerCapture(
                    event.pointerId,
                )
            } catch {
                // Захват указателя необязателен для работы.
            }
        }

        dragSessionRef.current = {
            pointerId:
                event.pointerId,
            startX:
                event.clientX,
            startY:
                event.clientY,
            startBounds:
                resizeBounds,
        }

        resizeSessionRef.current =
            null

        document.body.classList.add(
            'modal-dragging',
        )
    }

    function handleDragPointerMove(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        const session =
            dragSessionRef.current

        if (
            !resizeState.enabled
            || !session
            || session.pointerId
                !== event.pointerId
        ) {
            return
        }

        event.preventDefault()

        const limits =
            getResizeLimits(
                resizeState.options,
            )

        const maxLeft =
            Math.max(
                RESIZE_VIEWPORT_MARGIN,
                limits.viewportWidth
                    - RESIZE_VIEWPORT_MARGIN
                    - session.startBounds.width,
            )

        const maxTop =
            Math.max(
                RESIZE_VIEWPORT_MARGIN,
                limits.viewportHeight
                    - RESIZE_VIEWPORT_MARGIN
                    - session.startBounds.height,
            )

        setResizeBounds({
            ...session.startBounds,
            left: clamp(
                session.startBounds.left
                    + event.clientX
                    - session.startX,
                RESIZE_VIEWPORT_MARGIN,
                maxLeft,
            ),
            top: clamp(
                session.startBounds.top
                    + event.clientY
                    - session.startY,
                RESIZE_VIEWPORT_MARGIN,
                maxTop,
            ),
        })
    }

    function handleDragPointerEnd(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        const session =
            dragSessionRef.current

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
            typeof handle
                .hasPointerCapture
                === 'function'
            && handle.hasPointerCapture(
                event.pointerId,
            )
            && typeof handle
                .releasePointerCapture
                === 'function'
        ) {
            try {
                handle.releasePointerCapture(
                    event.pointerId,
                )
            } catch {
                // Браузер мог освободить указатель раньше.
            }
        }

        dragSessionRef.current =
            null

        document.body.classList.remove(
            'modal-dragging',
        )
    }


    return {
        resizeState,
        resizeBounds,
        maximized,
        cardStyle,
        toggleMaximized,
        handleResizePointerDown,
        handleResizePointerMove,
        handleResizePointerEnd,
        handleDragPointerDown,
        handleDragPointerMove,
        handleDragPointerEnd,
    }
}

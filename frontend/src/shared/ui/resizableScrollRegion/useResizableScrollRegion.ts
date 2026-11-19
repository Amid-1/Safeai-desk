// frontend/src/shared/ui/resizableScrollRegion/useResizableScrollRegion.ts
// ============================================================
// frontend/src/shared/ui/resizableScrollRegion/useResizableScrollRegion.ts
// ============================================================
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent, PointerEvent as ReactPointerEvent } from 'react'
import type { ResizableScrollRegionProps, DragState, LayoutMetrics } from './regionTypes'
import { beginBodyResizeStyle, restoreBodyResizeStyle, clamp, readSavedHeight } from './regionStorage'

const KEYBOARD_STEP = 32
const RESERVED_DRAG_RANGE = 96

export function useResizableScrollRegion({
    storageKey,
    defaultHeight = 420,
    minHeight = 220,
    maxHeight = 760,
    autoFitHeight,
    showLowerRequest,
    showUpperRequest,
    viewportResetKey,
    minUpperHeight = 100,
    preserveUpperContent = false,
    reserveLowerHeight = false,
    upperBoundaryPadding = 8,
}: ResizableScrollRegionProps) {
    const normalizedMinHeight =
        Math.min(
            minHeight,
            maxHeight,
        )

    const normalizedMaxHeight =
        Math.max(
            minHeight,
            maxHeight,
        )

    const rootRef =
        useRef<HTMLDivElement | null>(
            null,
        )

    const upperRef =
        useRef<HTMLDivElement | null>(
            null,
        )

    const viewportRef = useRef<HTMLDivElement | null>(null)
    const previousViewportResetKey = useRef(viewportResetKey)

    // При смене вкладки, фильтра или страницы возвращаем прокрутку к началу.
    // Перетаскивание и клавиатурное изменение высоты её не сбрасывают.
    useLayoutEffect(() => {
        if (previousViewportResetKey.current === viewportResetKey) return
        previousViewportResetKey.current = viewportResetKey
        if (viewportRef.current) viewportRef.current.scrollTop = 0
    }, [viewportResetKey])

    const dragRef =
        useRef<DragState | null>(
            null,
        )

    const [
        layoutMetrics,
        setLayoutMetrics,
    ] =
        useState<LayoutMetrics>({
            containerHeight: 0,
            preferredUpperHeight: null,
            protectedUpperHeight:
                Math.max(
                    0,
                    minUpperHeight,
                ),
        })

    /**
     * requestedHeight — пользовательское предпочтение.
     *
     * Оно специально отделено от фактической высоты.
     * При уменьшении окна нижняя область может стать меньше заданной,
     * но пользовательская высота сохраняется и вернётся при расширении окна.
     */
    const [
        requestedHeight,
        setRequestedHeight,
    ] =
        useState(() =>
            readSavedHeight(
                storageKey,
                defaultHeight,
                normalizedMinHeight,
                normalizedMaxHeight,
            ),
        )

    const renderedHeightRef =
        useRef(
            requestedHeight,
        )

    // Ручной выбор высоты хранится отдельно от прежнего ключа.
    // Короткие списки автоматически подстраиваются до ручного изменения.
    const [manualHeight, setManualHeight] = useState(() => {
        try {
            return window.localStorage.getItem(`${storageKey}:manual`) === '1'
        } catch {
            return false
        }
    })

    /** Программные команды изменения высоты реагируют только на новое значение счётчика. */
    const lastPanelRequests = useRef({
        showLowerRequest,
        showUpperRequest,
    })

    useEffect(() => {
        if (lastPanelRequests.current.showLowerRequest === showLowerRequest) {
            return
        }
        lastPanelRequests.current.showLowerRequest = showLowerRequest
        if (showLowerRequest === undefined) return

        // Фактическая высота ограничена viewport, пользовательская сохраняется.
        setRequestedHeight(normalizedMaxHeight)
        if (autoFitHeight != null) setManualHeight(true)
    }, [showLowerRequest, normalizedMaxHeight, autoFitHeight])

    useEffect(() => {
        if (lastPanelRequests.current.showUpperRequest === showUpperRequest) {
            return
        }
        lastPanelRequests.current.showUpperRequest = showUpperRequest
        if (showUpperRequest === undefined) return

        // Возвращаем максимум места фильтрам, не размонтируя их.
        setRequestedHeight(normalizedMinHeight)
        if (autoFitHeight != null) setManualHeight(true)
    }, [showUpperRequest, normalizedMinHeight, autoFitHeight])

    useEffect(() => {
        if (autoFitHeight == null) return
        try {
            if (manualHeight) {
                window.localStorage.setItem(`${storageKey}:manual`, '1')
            } else {
                window.localStorage.removeItem(`${storageKey}:manual`)
            }
        } catch {
            // Изменение высоты должно работать и без доступа к хранилищу.
        }
    }, [autoFitHeight, manualHeight, storageKey])


    const measureLayout =
        useCallback(() => {
            const root =
                rootRef.current

            const upperElement =
                upperRef.current

            if (
                !root
                || !upperElement
            ) {
                return
            }

            const rootRect =
                root.getBoundingClientRect()

            const nextContainerHeight =
                Math.max(
                    0,
                    Math.round(
                        rootRect.height,
                    ),
                )

            let nextProtectedUpperHeight =
                Math.max(
                    0,
                    minUpperHeight,
                )
            let nextPreferredUpperHeight: number | null = null

            if (preserveUpperContent) {
                const upperTop = upperElement.getBoundingClientRect().top
                const measureBoundary = (selector: string) => {
                    const boundary = upperElement.querySelector<HTMLElement>(selector)
                    return boundary ? Math.ceil(boundary.getBoundingClientRect().bottom
                        - upperTop + upperElement.scrollTop + Math.max(0, upperBoundaryPadding)) : null
                }
                const primary = measureBoundary('[data-resizable-upper-boundary]')
                if (primary !== null) nextProtectedUpperHeight = Math.max(nextProtectedUpperHeight, primary)
                // Optional second marker: initial/reset layout can show all controls,
                // while the drag limit protects only the primary controls.
                const preferred = measureBoundary('[data-resizable-upper-preferred-boundary]')
                if (preferred !== null) nextPreferredUpperHeight = Math.max(nextProtectedUpperHeight, preferred)
            }

            setLayoutMetrics(
                (current) => {
                    if (
                        current.containerHeight
                            === nextContainerHeight
                        && current.protectedUpperHeight
                            === nextProtectedUpperHeight
                        && current.preferredUpperHeight === nextPreferredUpperHeight
                    ) {
                        return current
                    }

                    return {
                        containerHeight:
                            nextContainerHeight,
                        protectedUpperHeight:
                            nextProtectedUpperHeight,
                        preferredUpperHeight: nextPreferredUpperHeight,
                    }
                },
            )
        }, [
            minUpperHeight,
            preserveUpperContent,
            upperBoundaryPadding,
        ])

    /**
     * После каждого React render перепроверяем semantic boundary.
     * State меняется только при реальном изменении размеров.
     */
    useLayoutEffect(() => {
        measureLayout()
    })

    useLayoutEffect(() => {
        const root =
            rootRef.current

        const upperElement =
            upperRef.current

        if (
            !root
            || !upperElement
        ) {
            return
        }

        if (
            typeof ResizeObserver
                === 'undefined'
        ) {
            window.addEventListener(
                'resize',
                measureLayout,
            )

            return () => {
                window.removeEventListener(
                    'resize',
                    measureLayout,
                )
            }
        }

        const observer =
            new ResizeObserver(() => {
                measureLayout()
            })

        observer.observe(root)
        observer.observe(upperElement)
        if (upperElement.firstElementChild) observer.observe(upperElement.firstElementChild)

        const boundary =
            upperElement.querySelector<HTMLElement>(
                '[data-resizable-upper-boundary]',
            )

        if (boundary) {
            observer.observe(boundary)
        }

        const mutationObserver =
            typeof MutationObserver
                === 'undefined'
                ? null
                : new MutationObserver(() => {
                    const nextBoundary =
                        upperElement.querySelector<HTMLElement>(
                            '[data-resizable-upper-boundary]',
                        )

                    if (nextBoundary) {
                        observer.observe(
                            nextBoundary,
                        )
                    }

                    measureLayout()
                })

        mutationObserver?.observe(
            upperElement,
            {
                childList: true,
                subtree: true,
                characterData: true,
                attributes: true,
                attributeFilter: ['open'],
            },
        )

        return () => {
            mutationObserver?.disconnect()
            observer.disconnect()
        }
    }, [
        measureLayout,
    ])

    const {
        containerHeight,
        protectedUpperHeight,
        preferredUpperHeight,
    } =
        layoutMetrics

    // On cramped screens, preserve the scrollable upper controls but reserve
    // space for a usable lower viewport instead of leaving only its chrome.
    const protectedUpperLimit = reserveLowerHeight
        ? Math.max(Math.min(minUpperHeight, containerHeight), containerHeight - normalizedMinHeight - RESERVED_DRAG_RANGE)
        : containerHeight
    const effectiveProtectedUpperHeight =
        containerHeight > 0
            ? Math.min(
                protectedUpperHeight,
                protectedUpperLimit,
            )
            : protectedUpperHeight

    const maximumAllowedByContainer =
        containerHeight > 0
            ? Math.max(
                0,
                containerHeight
                    - effectiveProtectedUpperHeight,
            )
            : normalizedMaxHeight

    const effectiveMaxHeight =
        Math.min(
            normalizedMaxHeight,
            maximumAllowedByContainer,
        )

    /**
     * На очень маленьком viewport lower может стать меньше
     * штатного minHeight, чтобы split никогда не вытолкнул body
     * за границы viewport.
     */
    const effectiveMinHeight =
        Math.min(
            normalizedMinHeight,
            effectiveMaxHeight,
        )

    const automaticHeight = autoFitHeight != null && preferredUpperHeight !== null && containerHeight > 0
        ? Math.min(autoFitHeight, containerHeight - preferredUpperHeight)
        : autoFitHeight
    const renderedHeight =
        clamp(
            automaticHeight != null && !manualHeight
                ? automaticHeight
                : requestedHeight,
            effectiveMinHeight,
            effectiveMaxHeight,
        )

    useLayoutEffect(() => {
        renderedHeightRef.current =
            renderedHeight
    }, [
        renderedHeight,
    ])

    const setUserHeight =
        useCallback((
            nextHeight: number,
        ) => {
            const next =
                clamp(
                    nextHeight,
                    effectiveMinHeight,
                    effectiveMaxHeight,
                )

            setRequestedHeight(next)
            if (autoFitHeight != null) setManualHeight(true)
        }, [
            effectiveMinHeight,
            effectiveMaxHeight,
            autoFitHeight,
        ])

    useEffect(() => {
        try {
            window.localStorage.setItem(
                storageKey,
                String(
                    requestedHeight,
                ),
            )
        } catch {
            /**
             * При недоступном localStorage resize всё равно работает.
             */
        }
    }, [
        requestedHeight,
        storageKey,
    ])

    const restoreBodyState =
        useCallback(() => {
            const drag =
                dragRef.current

            if (!drag) {
                return
            }

            restoreBodyResizeStyle({
                cursor:
                    drag.previousBodyCursor,
                userSelect:
                    drag.previousBodyUserSelect,
            })

            dragRef.current =
                null
        }, [])

    useEffect(() => {
        return () => {
            restoreBodyState()
        }
    }, [
        restoreBodyState,
    ])

    function beginResize(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        /**
         * Resize только ЛКМ.
         */
        if (event.button !== 0) {
            return
        }

        event.preventDefault()

        try {
            event.currentTarget
                .setPointerCapture(
                    event.pointerId,
                )
        } catch {
            /**
             * Pointer capture не является условием работоспособности.
             */
        }

        const previousBodyStyle =
            beginBodyResizeStyle()

        dragRef.current = {
            pointerId:
                event.pointerId,

            startY:
                event.clientY,

            startHeight:
                renderedHeightRef.current,

            previousBodyCursor:
                previousBodyStyle.cursor,

            previousBodyUserSelect:
                previousBodyStyle.userSelect,
        }
    }

    function moveResize(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        const drag =
            dragRef.current

        if (
            !drag
            || drag.pointerId
                !== event.pointerId
        ) {
            return
        }

        /**
         * Lower закреплён снизу.
         *
         * Мышь вверх  -> lower увеличивается вверх.
         * Мышь вниз   -> lower уменьшается вниз.
         *
         * Это настоящий split layout, не overlay.
         */
        const nextHeight =
            drag.startHeight
            + drag.startY
            - event.clientY

        setUserHeight(
            nextHeight,
        )
    }

    function finishResize(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        const drag =
            dragRef.current

        if (
            !drag
            || drag.pointerId
                !== event.pointerId
        ) {
            return
        }

        try {
            if (
                event.currentTarget
                    .hasPointerCapture(
                        event.pointerId,
                    )
            ) {
                event.currentTarget
                    .releasePointerCapture(
                        event.pointerId,
                    )
            }
        } catch {
            /**
             * Pointer мог быть освобождён самим браузером.
             */
        }

        restoreBodyState()
    }

    /**
     * Двойной щелчок возвращает исходную высоту нижней панели.
     * Ограничения viewport и защищённого верхнего блока сохраняются.
     */
    function resetHeight() {
        setRequestedHeight(defaultHeight)
        if (autoFitHeight != null) setManualHeight(false)
    }

    function handleKeyDown(
        event:
            KeyboardEvent<HTMLDivElement>,
    ) {
        if (
            event.key
                === 'ArrowUp'
        ) {
            event.preventDefault()

            setUserHeight(
                renderedHeightRef.current
                + KEYBOARD_STEP,
            )

            return
        }

        if (
            event.key
                === 'ArrowDown'
        ) {
            event.preventDefault()

            setUserHeight(
                renderedHeightRef.current
                - KEYBOARD_STEP,
            )

            return
        }

        if (
            event.key
                === 'Home'
        ) {
            event.preventDefault()

            setUserHeight(
                effectiveMinHeight,
            )

            return
        }

        if (
            event.key
                === 'End'
        ) {
            event.preventDefault()

            setUserHeight(
                effectiveMaxHeight,
            )
        }
    }

        return {
        rootRef, upperRef, viewportRef, manualHeight, renderedHeight,
        effectiveProtectedUpperHeight, effectiveMinHeight, effectiveMaxHeight,
        beginResize, moveResize, finishResize, restoreBodyState,
        resetHeight, handleKeyDown,
    }
}

// ============================================================
// frontend/src/components/ResizableScrollRegion.tsx
// ============================================================

import {
    useCallback,
    useEffect,
    useLayoutEffect,
    useRef,
    useState,
} from 'react'
import type {
    CSSProperties,
    KeyboardEvent,
    PointerEvent as ReactPointerEvent,
    ReactNode,
} from 'react'
import './ResizableScrollRegion.css'

type ResizableScrollRegionProps = {
    /**
     * Верхняя часть workspace:
     * header, filters, cards, tabs, descriptions.
     */
    upper: ReactNode

    /**
     * Прокручиваемое содержимое нижней области.
     * Обычно table / list.
     */
    children: ReactNode

    /**
     * Неподвижная нижняя строка:
     * pagination / summary.
     *
     * Footer не входит в scroll viewport.
     */
    footer?: ReactNode

    storageKey: string
    label: string

    className?: string
    upperClassName?: string
    lowerClassName?: string
    viewportClassName?: string
    footerClassName?: string

    /**
     * Желаемая высота всей нижней области:
     * separator + viewport + footer.
     */
    defaultHeight?: number
    minHeight?: number
    maxHeight?: number

    /**
     * Минимальный резерв для верхней области.
     * Используется всегда как абсолютный fallback.
     */
    minUpperHeight?: number

    /**
     * Если true, component ищет внутри upper маркер:
     *
     * data-resizable-upper-boundary
     *
     * и не позволяет нижней области подняться выше нижней
     * границы этого маркера.
     *
     * Важно: измеряется именно semantic boundary, а НЕ scrollHeight
     * всего upper. Поэтому свободное место внутри flex-layout не
     * блокирует движение separator.
     */
    preserveUpperContent?: boolean

    /**
     * Небольшой визуальный зазор между защищённой границей upper
     * и separator.
     */
    upperBoundaryPadding?: number
}

type DragState = {
    pointerId: number
    startY: number
    startHeight: number
    previousBodyCursor: string
    previousBodyUserSelect: string
}

type LayoutMetrics = {
    containerHeight: number
    protectedUpperHeight: number
}

const KEYBOARD_STEP = 32

function clamp(
    value: number,
    minimum: number,
    maximum: number,
): number {
    return Math.min(
        maximum,
        Math.max(
            minimum,
            value,
        ),
    )
}

function readSavedHeight(
    storageKey: string,
    fallback: number,
    minimum: number,
    maximum: number,
): number {
    if (typeof window === 'undefined') {
        return clamp(
            fallback,
            minimum,
            maximum,
        )
    }

    try {
        const raw =
            window.localStorage.getItem(
                storageKey,
            )

        if (raw === null) {
            return clamp(
                fallback,
                minimum,
                maximum,
            )
        }

        const value =
            Number(raw)

        return Number.isFinite(value)
            ? clamp(
                value,
                minimum,
                maximum,
            )
            : clamp(
                fallback,
                minimum,
                maximum,
            )
    } catch {
        return clamp(
            fallback,
            minimum,
            maximum,
        )
    }
}

function ResizableScrollRegion({
    upper,
    children,
    footer,

    storageKey,
    label,

    className = '',
    upperClassName = '',
    lowerClassName = '',
    viewportClassName = '',
    footerClassName = '',

    defaultHeight = 420,
    minHeight = 220,
    maxHeight = 760,

    minUpperHeight = 100,
    preserveUpperContent = false,
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
     * Если окно временно уменьшилось, layout может показать lower
     * меньше requestedHeight, но сохранённое пользовательское значение
     * не затирается и восстановится при увеличении viewport.
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

            if (preserveUpperContent) {
                const boundary =
                    upperElement.querySelector<HTMLElement>(
                        '[data-resizable-upper-boundary]',
                    )

                if (boundary) {
                    const upperRect =
                        upperElement.getBoundingClientRect()

                    const boundaryRect =
                        boundary.getBoundingClientRect()

                    /**
                     * Координата boundary в системе самого upper.
                     * upper.scrollTop добавляется, чтобы измерение
                     * не зависело от текущего положения внутреннего scroll.
                     */
                    const boundaryBottomWithinUpper =
                        boundaryRect.bottom
                        - upperRect.top
                        + upperElement.scrollTop

                    nextProtectedUpperHeight =
                        Math.max(
                            nextProtectedUpperHeight,
                            Math.ceil(
                                boundaryBottomWithinUpper
                                + Math.max(
                                    0,
                                    upperBoundaryPadding,
                                ),
                            ),
                        )
                }
            }

            setLayoutMetrics(
                (current) => {
                    if (
                        current.containerHeight
                            === nextContainerHeight
                        && current.protectedUpperHeight
                            === nextProtectedUpperHeight
                    ) {
                        return current
                    }

                    return {
                        containerHeight:
                            nextContainerHeight,
                        protectedUpperHeight:
                            nextProtectedUpperHeight,
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
    } =
        layoutMetrics

    const effectiveProtectedUpperHeight =
        containerHeight > 0
            ? Math.min(
                protectedUpperHeight,
                containerHeight,
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

    const renderedHeight =
        clamp(
            requestedHeight,
            effectiveMinHeight,
            effectiveMaxHeight,
        )

    renderedHeightRef.current =
        renderedHeight

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

            setRequestedHeight(
                next,
            )
        }, [
            effectiveMinHeight,
            effectiveMaxHeight,
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

            document.body.style.cursor =
                drag.previousBodyCursor

            document.body.style.userSelect =
                drag.previousBodyUserSelect

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

        dragRef.current = {
            pointerId:
                event.pointerId,

            startY:
                event.clientY,

            startHeight:
                renderedHeightRef.current,

            previousBodyCursor:
                document.body.style.cursor,

            previousBodyUserSelect:
                document.body.style.userSelect,
        }

        document.body.style.cursor =
            'row-resize'

        document.body.style.userSelect =
            'none'
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

    return (
        <div
            ref={rootRef}
            className={[
                'resizable-scroll-region',
                preserveUpperContent
                    ? 'resizable-scroll-region--preserve-upper'
                    : '',
                className,
            ]
                .filter(Boolean)
                .join(' ')}
            style={{
                '--resizable-scroll-height':
                    `${renderedHeight}px`,

                '--resizable-upper-min-height':
                    `${effectiveProtectedUpperHeight}px`,
            } as CSSProperties}
        >
            <div
                ref={upperRef}
                className={[
                    'resizable-scroll-region__upper',
                    upperClassName,
                ]
                    .filter(Boolean)
                    .join(' ')}
            >
                {upper}
            </div>

            <div
                className={[
                    'resizable-scroll-region__lower',
                    lowerClassName,
                ]
                    .filter(Boolean)
                    .join(' ')}
            >
                <div
                    className={
                        'resizable-scroll-region__handle'
                    }
                    role="separator"
                    aria-label={
                        `Изменить размер области: ${label}`
                    }
                    aria-orientation="horizontal"
                    aria-valuemin={
                        Math.round(
                            effectiveMinHeight,
                        )
                    }
                    aria-valuemax={
                        Math.round(
                            effectiveMaxHeight,
                        )
                    }
                    aria-valuenow={
                        Math.round(
                            renderedHeight,
                        )
                    }
                    tabIndex={0}
                    onPointerDown={
                        beginResize
                    }
                    onPointerMove={
                        moveResize
                    }
                    onPointerUp={
                        finishResize
                    }
                    onPointerCancel={
                        finishResize
                    }
                    onLostPointerCapture={
                        restoreBodyState
                    }
                    onKeyDown={
                        handleKeyDown
                    }
                >
                    <span
                        aria-hidden="true"
                    />
                </div>

                <div
                    className={[
                        'resizable-scroll-region__viewport',
                        viewportClassName,
                    ]
                        .filter(Boolean)
                        .join(' ')}
                >
                    {children}
                </div>

                {footer !== undefined
                    && footer !== null
                    && (
                        <div
                            className={[
                                'resizable-scroll-region__footer',
                                footerClassName,
                            ]
                                .filter(Boolean)
                                .join(' ')}
                        >
                            {footer}
                        </div>
                    )}
            </div>
        </div>
    )
}

export default ResizableScrollRegion

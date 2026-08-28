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
    /*
     * Обычная верхняя часть страницы:
     * header, cards, filters, tabs, descriptions.
     */
    upper: ReactNode

    /*
     * Прокручиваемое содержимое нижней области.
     * Обычно это table.
     */
    children: ReactNode

    /*
     * Неподвижная нижняя строка:
     * pagination / summary.
     *
     * Footer НЕ входит в scroll viewport.
     */
    footer?: ReactNode

    storageKey: string
    label: string

    className?: string
    upperClassName?: string
    lowerClassName?: string
    viewportClassName?: string
    footerClassName?: string

    /*
     * Высота нижней области:
     * separator + viewport + footer.
     */
    defaultHeight?: number
    minHeight?: number
    maxHeight?: number

    /*
     * Сколько минимум нужно оставить
     * верхней части страницы.
     */
    minUpperHeight?: number
}

type DragState = {
    pointerId: number
    startY: number
    startHeight: number
    previousBodyCursor: string
    previousBodyUserSelect: string
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
    if (
        typeof window
        === 'undefined'
    ) {
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

        const value = Number(raw)

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

    const dragRef =
        useRef<DragState | null>(
            null,
        )

    const [
        containerHeight,
        setContainerHeight,
    ] = useState(0)

    const [
        height,
        setHeight,
    ] = useState(() =>
        readSavedHeight(
            storageKey,
            defaultHeight,
            normalizedMinHeight,
            normalizedMaxHeight,
        ),
    )

    const heightRef =
        useRef(height)

    /*
     * Нижняя область не должна уничтожить
     * верхнюю часть страницы.
     */
    const maximumAllowedByContainer =
        containerHeight > 0
            ? Math.max(
                0,
                containerHeight
                - minUpperHeight,
            )
            : normalizedMaxHeight

    const effectiveMaxHeight =
        Math.min(
            normalizedMaxHeight,
            maximumAllowedByContainer,
        )

    /*
     * На очень маленьком viewport допустимо
     * стать меньше обычного minHeight,
     * чтобы layout не переполнился.
     */
    const effectiveMinHeight =
        Math.min(
            normalizedMinHeight,
            effectiveMaxHeight,
        )

    useLayoutEffect(() => {
        const root =
            rootRef.current

        if (!root) {
            return
        }

        const measure = () => {
            const nextHeight =
                root
                    .getBoundingClientRect()
                    .height

            setContainerHeight(
                Math.max(
                    0,
                    nextHeight,
                ),
            )
        }

        measure()

        if (
            typeof ResizeObserver
            === 'undefined'
        ) {
            window.addEventListener(
                'resize',
                measure,
            )

            return () => {
                window.removeEventListener(
                    'resize',
                    measure,
                )
            }
        }

        const observer =
            new ResizeObserver(
                measure,
            )

        observer.observe(root)

        return () => {
            observer.disconnect()
        }
    }, [])

    const setClampedHeight =
        useCallback((
            nextHeight: number,
        ) => {
            const next =
                clamp(
                    nextHeight,
                    effectiveMinHeight,
                    effectiveMaxHeight,
                )

            heightRef.current =
                next

            setHeight(next)
        }, [
            effectiveMinHeight,
            effectiveMaxHeight,
        ])

    /*
     * Перепроверяем высоту после resize окна.
     */
    useEffect(() => {
        setClampedHeight(
            heightRef.current,
        )
    }, [
        effectiveMinHeight,
        effectiveMaxHeight,
        setClampedHeight,
    ])

    /*
     * Сохраняем пользовательский размер.
     */
    useEffect(() => {
        heightRef.current =
            height

        try {
            window.localStorage.setItem(
                storageKey,
                String(height),
            )
        } catch {
            /*
             * При недоступном localStorage
             * resize всё равно работает.
             */
        }
    }, [
        height,
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
        /*
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
            /*
             * Pointer capture не является
             * условием работоспособности layout.
             */
        }

        dragRef.current = {
            pointerId:
                event.pointerId,

            startY:
                event.clientY,

            startHeight:
                heightRef.current,

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

        /*
         * КЛЮЧЕВАЯ ФОРМУЛА.
         *
         * Нижняя область закреплена снизу.
         *
         * Двигаем мышь ВВЕРХ:
         *
         *     clientY уменьшается
         *     height увеличивается
         *
         * Нижняя область растёт ВВЕРХ.
         *
         * Двигаем мышь ВНИЗ:
         *
         *     clientY увеличивается
         *     height уменьшается
         *
         * Верхняя часть страницы раскрывается.
         */
        const nextHeight =
            drag.startHeight
            + drag.startY
            - event.clientY

        setClampedHeight(
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
            /*
             * Pointer мог быть освобождён
             * самим браузером.
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

            setClampedHeight(
                heightRef.current
                + KEYBOARD_STEP,
            )

            return
        }

        if (
            event.key
            === 'ArrowDown'
        ) {
            event.preventDefault()

            setClampedHeight(
                heightRef.current
                - KEYBOARD_STEP,
            )

            return
        }

        if (
            event.key
            === 'Home'
        ) {
            event.preventDefault()

            setClampedHeight(
                effectiveMinHeight,
            )

            return
        }

        if (
            event.key
            === 'End'
        ) {
            event.preventDefault()

            setClampedHeight(
                effectiveMaxHeight,
            )
        }
    }

    return (
        <div
            ref={rootRef}
            className={[
                'resizable-scroll-region',
                className,
            ]
                .filter(Boolean)
                .join(' ')}
            style={{
                '--resizable-scroll-height':
                    `${height}px`,

                '--resizable-upper-min-height':
                    `${minUpperHeight}px`,
            } as CSSProperties}
        >
            <div
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
                            height,
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
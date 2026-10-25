// frontend/src/shared/ui/modal/modalGeometry.ts
// Ответственность модуля: геометрия и масштабирование окна.

export type ModalResizeOptions = {
    initialWidth: number
    initialHeight: number
    minWidth: number
    minHeight: number
    maxWidth?: number
    maxHeight?: number

    /**
     * Масштабировать текст и элементы управления вместе с окном.
     */
    scaleContent?: boolean

    minScale?: number
    maxScale?: number

    /**
     * Показывать системную кнопку разворота и восстановления окна.
     */
    maximizable?: boolean
}

export type ModalBounds = {
    left: number
    top: number
    width: number
    height: number
}

export type ResizeDirection =
    | 'n'
    | 'ne'
    | 'e'
    | 'se'
    | 's'
    | 'sw'
    | 'w'
    | 'nw'

export type ResizeSession = {
    pointerId: number
    direction: ResizeDirection
    startX: number
    startY: number
    startBounds: ModalBounds
}

export type DragSession = {
    pointerId: number
    startX: number
    startY: number
    startBounds: ModalBounds
}

export type ResolvedModalResize =
    | {
        enabled: false
    }
    | {
        enabled: true
        options: ModalResizeOptions
    }

export const RESIZE_VIEWPORT_MARGIN = 12
const DEFAULT_MIN_SCALE = 0.78
const DEFAULT_MAX_SCALE = 1.20
const CONTENT_FIT_HEADROOM = 0.98
const COMPACT_CONTENT_FIT_HEADROOM = 0.94

export function createMaximizedModalBounds(): ModalBounds {
    return {
        left: 0,
        top: 0,
        width: Math.max(320, window.innerWidth),
        height: Math.max(320, window.innerHeight),
    }
}

export function clamp(
    value: number,
    minimum: number,
    maximum: number,
): number {
    return Math.min(
        Math.max(
            value,
            minimum,
        ),
        maximum,
    )
}

export function getResizeLimits(
    resize:
        ModalResizeOptions,
) {
    const viewportWidth =
        Math.max(
            0,
            window.innerWidth,
        )

    const viewportHeight =
        Math.max(
            0,
            window.innerHeight,
        )

    const availableWidth =
        Math.max(
            1,
            viewportWidth
            - RESIZE_VIEWPORT_MARGIN
            * 2,
        )

    const availableHeight =
        Math.max(
            1,
            viewportHeight
            - RESIZE_VIEWPORT_MARGIN
            * 2,
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

export function createInitialModalBounds(
    resize:
        ModalResizeOptions,
): ModalBounds {
    const limits =
        getResizeLimits(
            resize,
        )

    const width =
        clamp(
            resize.initialWidth,
            limits.minWidth,
            limits.maxWidth,
        )

    const height =
        clamp(
            resize.initialHeight,
            limits.minHeight,
            limits.maxHeight,
        )

    return {
        left:
            (
                limits.viewportWidth
                - width
            )
            / 2,

        top:
            (
                limits.viewportHeight
                - height
            )
            / 2,

        width,
        height,
    }
}

export function fitModalBoundsToViewport(
    bounds:
        ModalBounds,
    resize:
        ModalResizeOptions,
): ModalBounds {
    const limits =
        getResizeLimits(
            resize,
        )

    const width =
        clamp(
            bounds.width,
            limits.minWidth,
            limits.maxWidth,
        )

    const height =
        clamp(
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
        left:
            clamp(
                bounds.left,
                RESIZE_VIEWPORT_MARGIN,
                maxLeft,
            ),

        top:
            clamp(
                bounds.top,
                RESIZE_VIEWPORT_MARGIN,
                maxTop,
            ),

        width,
        height,
    }
}

export function resizeModalBounds(
    session:
        ResizeSession,
    clientX:
        number,
    clientY:
        number,
    resize:
        ModalResizeOptions,
): ModalBounds {
    const {
        direction,
        startX,
        startY,
        startBounds,
    } =
        session

    const limits =
        getResizeLimits(
            resize,
        )

    const deltaX =
        clientX
        - startX

    const deltaY =
        clientY
        - startY

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

    let left =
        startLeft

    let right =
        startRight

    let top =
        startTop

    let bottom =
        startBottom

    if (
        direction.includes(
            'e',
        )
    ) {
        right =
            clamp(
                startRight
                + deltaX,

                startLeft
                + limits.minWidth,

                Math.min(
                    limits.viewportWidth
                    - RESIZE_VIEWPORT_MARGIN,

                    startLeft
                    + limits.maxWidth,
                ),
            )
    }

    if (
        direction.includes(
            'w',
        )
    ) {
        left =
            clamp(
                startLeft
                + deltaX,

                Math.max(
                    RESIZE_VIEWPORT_MARGIN,

                    startRight
                    - limits.maxWidth,
                ),

                startRight
                - limits.minWidth,
            )
    }

    if (
        direction.includes(
            's',
        )
    ) {
        bottom =
            clamp(
                startBottom
                + deltaY,

                startTop
                + limits.minHeight,

                Math.min(
                    limits.viewportHeight
                    - RESIZE_VIEWPORT_MARGIN,

                    startTop
                    + limits.maxHeight,
                ),
            )
    }

    if (
        direction.includes(
            'n',
        )
    ) {
        top =
            clamp(
                startTop
                + deltaY,

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
        width:
            right
            - left,
        height:
            bottom
            - top,
    }
}

/**
 * Обе оси участвуют в масштабе, но ограничивающая ось получает основной
 * вес. Благодаря этому расширение только по ширине или высоте слегка
 * увеличивает содержимое, не вызывая скрытого переполнения по второй оси.
 *
 * Поэтому:
 * - расширение по любой грани увеличивает содержимое;
 * - уменьшение по любой грани уменьшает текста и элементов управления;
 * - изменение размера за угол учитывает ширину и высоту одновременно.
 */
export function calculateResizeScale(
    bounds:
        ModalBounds,
    resize:
        ModalResizeOptions,
): number {
    const widthRatio =
        bounds.width
        / resize.initialWidth

    const heightRatio =
        bounds.height
        / resize.initialHeight

    const limitingRatio =
        Math.min(
            widthRatio,
            heightRatio,
        )

    const spaciousRatio =
        Math.max(
            widthRatio,
            heightRatio,
        )

    const capacityAwareScale =
        Math.pow(
            limitingRatio,
            0.85,
        )
        * Math.pow(
            spaciousRatio,
            0.15,
        )
        * (
            limitingRatio < 1
                ? COMPACT_CONTENT_FIT_HEADROOM
                : CONTENT_FIT_HEADROOM
        )

    const minimumScale =
        Math.min(
            resize.minScale
                ?? DEFAULT_MIN_SCALE,

            resize.maxScale
                ?? DEFAULT_MAX_SCALE,
        )

    const maximumScale =
        Math.max(
            resize.minScale
                ?? DEFAULT_MIN_SCALE,

            resize.maxScale
                ?? DEFAULT_MAX_SCALE,
        )

    return clamp(
        capacityAwareScale,
        minimumScale,
        maximumScale,
    )
}

// ============================================================
// frontend/src/components/resizableScrollRegion/regionStorage.ts
// ============================================================
import type { BodyDragStyle } from './regionTypes'

export function beginBodyResizeStyle(): BodyDragStyle {
    const body = document.body

    const previous = {
        cursor: body.style.cursor,
        userSelect: body.style.userSelect,
    }

    body.style.cursor = 'row-resize'
    body.style.userSelect = 'none'

    return previous
}

export function restoreBodyResizeStyle(
    previous: BodyDragStyle,
) {
    const body = document.body

    body.style.cursor = previous.cursor
    body.style.userSelect = previous.userSelect
}

export function clamp(
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

export function readSavedHeight(
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

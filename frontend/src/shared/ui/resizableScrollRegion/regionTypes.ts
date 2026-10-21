// frontend/src/shared/ui/resizableScrollRegion/regionTypes.ts
// ============================================================
// frontend/src/shared/ui/resizableScrollRegion/regionTypes.ts
// ============================================================
import type { ReactNode } from 'react'

export type ResizableScrollRegionProps = {
    /**
     * Верхняя часть рабочего пространства: заголовок, фильтры,
     * карточки, вкладки и описания.
     */
    upper: ReactNode

    /**
     * Прокручиваемое содержимое нижней области.
     * Обычно таблица или список.
     */
    children: ReactNode

    /**
     * Неподвижная нижняя строка:
     * пагинация или сводка.
     *
     * Нижняя строка не входит в прокручиваемую область.
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
     * разделитель + прокручиваемая область + нижняя строка.
     */
    defaultHeight?: number
    minHeight?: number
    maxHeight?: number
    /** Автоматическая высота панели до первого ручного изменения. */
    autoFitHeight?: number

    /**
     * Необязательные счётчики команд: изменение значения разворачивает
     * отчёт или возвращает место фильтрам. Исходное значение командой
     * не считается; существующие потребители работают без них.
     */
    showLowerRequest?: number
    showUpperRequest?: number
    /** Сбрасывать прокрутку только при смене отчёта, вкладки, страницы или фильтра. */
    viewportResetKey?: string | number

    /**
     * Минимальный резерв для верхней области.
     * Используется всегда как абсолютный fallback.
     */
    minUpperHeight?: number

    /**
     * При включении ищется маркер верхней области:
     *
     * data-resizable-upper-boundary
     *
     * и не позволяет нижней области подняться выше нижней
     * границы этого маркера.
     *
     * Важно: измеряется граница значимого содержимого, а не scrollHeight
     * всей верхней области. Свободное пространство внутри flex
     * не блокирует перемещение разделителя.
     */
    preserveUpperContent?: boolean

    /**
     * Небольшой зазор между защищённой границей и разделителем.
     */
    upperBoundaryPadding?: number
}

export type DragState = {
    pointerId: number
    startY: number
    startHeight: number
    previousBodyCursor: string
    previousBodyUserSelect: string
}

export type LayoutMetrics = {
    containerHeight: number
    protectedUpperHeight: number
}

export type BodyDragStyle = {
    cursor: string
    userSelect: string
}

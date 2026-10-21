// frontend/src/features/usage/controller/useUsageWorkspace.ts
// Управление режимами просмотра, drag/keyboard-переходами и измерением высоты результата.
import { useLayoutEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent, KeyboardEvent as ReactKeyboardEvent } from 'react'
import type { UsageReportTab } from '../lib/adminUsage.helpers'
import { autoFitUsageHeight } from '../lib/usagePanelHeight'

type UseUsageWorkspaceInput = {
    showReportRows: boolean
    tab: UsageReportTab
    rowsLength: number
}

export function useUsageWorkspace({ showReportRows, tab, rowsLength }: UseUsageWorkspaceInput) {
    // В обычном режиме используется непрерывная прокрутка без обрезания строк.
    // Ограниченную область включаем только после явного действия пользователя.
    const [focusedResults, setFocusedResults] = useState(false)
    const pageRef = useRef<HTMLDivElement | null>(null)
    const [showLowerRequest, setShowLowerRequest] = useState(0)
    const [showUpperRequest, setShowUpperRequest] = useState(0)
    const resultsRef = useRef<HTMLDivElement | null>(null)

    useLayoutEffect(() => {
        if (pageRef.current) pageRef.current.scrollTop = 0
    }, [focusedResults])
    const [naturalResultHeight, setNaturalResultHeight] = useState<number | null>(null)

    // Разделитель доступен в обычной разметке и для мыши, и с клавиатуры.
    // До начала штатной обработки перетаскивания активируем режим разделения.
    // Собственная реализация drag не создаётся: сохраняется существующий обработчик.
    const onWorkspacePointerDownCapture = (
        event: ReactPointerEvent<HTMLDivElement>,
    ) => {
        if (focusedResults || !showReportRows || event.button !== 0) return
        const target = event.target
        if (!(target instanceof Element)) return
        if (!target.closest('.resizable-scroll-region__handle')) return
        setFocusedResults(true)
    }

    const onWorkspaceKeyDownCapture = (
        event: ReactKeyboardEvent<HTMLDivElement>,
    ) => {
        if (focusedResults || !showReportRows) return
        if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
        const target = event.target
        if (!(target instanceof Element)) return
        if (!target.closest('.resizable-scroll-region__handle')) return
        setFocusedResults(true)
    }

    // Измеряем реальную высоту итогов, диаграмм, экспорта и строк отчёта.
    // Высота, изменённая пользователем вручную, имеет приоритет над autoFit.
    useLayoutEffect(() => {
        const content = resultsRef.current
        if (!showReportRows || !content) {
            setNaturalResultHeight(null)
            return
        }

        const measure = () => {
            const footer = content.closest('.resizable-scroll-region')
                ?.querySelector<HTMLElement>('.resizable-scroll-region__footer')
            const desired = Math.ceil(
                Math.max(content.scrollHeight, content.getBoundingClientRect().height)
                + (footer?.getBoundingClientRect().height ?? 45)
                + 32, // разделитель, внутренние отступы и запас
            )
            setNaturalResultHeight(old => old === desired ? old : desired)
        }

        measure()
        if (typeof ResizeObserver === 'undefined') {
            window.addEventListener('resize', measure)
            return () => window.removeEventListener('resize', measure)
        }
        const observer = new ResizeObserver(measure)
        observer.observe(content)
        const footer = content.closest('.resizable-scroll-region')
            ?.querySelector<HTMLElement>('.resizable-scroll-region__footer')
        if (footer) observer.observe(footer)
        return () => observer.disconnect()
    }, [showReportRows, tab, rowsLength])

    // Стартовая высота остаётся компактной; развернуть область можно отдельно.
    const fittedResultsHeight = showReportRows
        ? autoFitUsageHeight(naturalResultHeight, tab)
        : 200


    function expandResults() {
        setFocusedResults(true)
        setShowLowerRequest(value => value + 1)
    }

    function restoreFilters() {
        setFocusedResults(false)
        setShowUpperRequest(value => value + 1)
    }

    return {
        focusedResults, pageRef, resultsRef, showLowerRequest, showUpperRequest,
        fittedResultsHeight, onWorkspacePointerDownCapture, onWorkspaceKeyDownCapture,
        expandResults, restoreFilters,
    }
}

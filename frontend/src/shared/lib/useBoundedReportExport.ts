// frontend/src/shared/lib/useBoundedReportExport.ts
import { useLayoutEffect, useRef, useState } from 'react'

export const REPORT_EXPORT_LIMIT = 100

export function useBoundedReportExport(scope: string) {
    const active = useRef<AbortController | null>(null)
    const [state, setState] = useState({ scope, pending: false, error: '' })

    useLayoutEffect(() => () => {
        active.current?.abort()
        active.current = null
    }, [scope])

    async function run<T>(load: (signal: AbortSignal) => Promise<T[] | null>, download: (rows: T[]) => void) {
        if (active.current) return
        const controller = new AbortController()
        active.current = controller
        setState({ scope, pending: true, error: '' })
        try {
            const rows = await load(controller.signal)
            if (controller.signal.aborted) return
            if (rows === null || rows.length > REPORT_EXPORT_LIMIT) {
                setState({ scope, pending: false, error: 'Больше 100 записей. Уточните фильтры или скачайте текущую страницу.' })
            } else if (!rows.length) {
                setState({ scope, pending: false, error: 'Нет записей для выгрузки.' })
            } else {
                download(rows)
                setState({ scope, pending: false, error: '' })
            }
        } catch {
            if (!controller.signal.aborted) {
                setState({ scope, pending: false, error: 'Не удалось выгрузить отчёт. Попробуйте повторно.' })
            }
        } finally {
            if (active.current === controller) active.current = null
        }
    }

    return { run, pending: state.scope === scope && state.pending, error: state.scope === scope ? state.error : '' }
}

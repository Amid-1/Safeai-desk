// frontend/src/features/usage/controller/useUsageExport.ts
import { useEffect, useRef, useState } from 'react'
import { downloadUsageExport, readUsageExport, startUsageExport,
    type ExportFilter, type ExportJob } from '../api/usageExportApi'

export function useUsageExport(scopeKey: string) {
    const [result, setResult] = useState<{key: string; job: ExportJob | null; error: string; busy: boolean} | null>(null)
    const operation = useRef<AbortController | null>(null)
    useEffect(() => {
        setResult(null)
        return () => operation.current?.abort()
    }, [scopeKey])
    const current = result?.key === scopeKey ? result : null
    const job = current?.job
    const pending = Boolean(current?.busy || job?.state === 'QUEUED' || job?.state === 'RUNNING')

    async function run(filter: ExportFilter) {
        if (pending) return
        operation.current?.abort()
        const controller = new AbortController()
        operation.current = controller
        const signal = controller.signal
        const update = (next: ExportJob | null, error = '', busy = false) => {
            if (!signal.aborted) setResult({key: scopeKey, job: next, error, busy})
        }
        update(null, '', true)
        try {
            let next = await startUsageExport(filter, signal)
            update(next)
            let polls = 0
            while (!signal.aborted && (next.state === 'QUEUED' || next.state === 'RUNNING')) {
                if (++polls > 450) throw new Error('Срок ожидания истёк')
                await new Promise<void>(resolve => window.setTimeout(resolve, 2000))
                if (signal.aborted) return
                next = await readUsageExport(next.id, signal)
                update(next)
            }
            if (next.state === 'FAILED') {
                update(next, next.failure === 'LIMIT_EXCEEDED'
                    ? 'Отчёт превышает 10 000 строк или 8 МиБ. Уточните фильтры.'
                    : 'Не удалось подготовить CSV. Повторите запрос.')
            }
        } catch {
            update(null, 'Экспорт недоступен. Возможно, очередь занята или задание истекло.')
        }
    }

    async function download() {
        if (!job || job.state !== 'READY' || current?.busy) return
        operation.current?.abort()
        const controller = new AbortController()
        operation.current = controller
        setResult({key: scopeKey, job, error: '', busy: true})
        try {
            await downloadUsageExport(job.id, controller.signal)
            if (!controller.signal.aborted) setResult({key: scopeKey, job, error: '', busy: false})
        } catch {
            if (!controller.signal.aborted) setResult({key: scopeKey, job: null,
                error: 'Файл недоступен или срок хранения истёк. Создайте новый экспорт.', busy: false})
        }
    }
    return {run, download, pending, ready: job?.state === 'READY', error: current?.error ?? ''}
}

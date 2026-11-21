// frontend/src/features/usage/controller/useUsageExport.ts
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../../shared/api/http'
import { downloadUsageExport, readUsageExport, startUsageExport,
    type ExportFilter, type ExportJob } from '../api/usageExportApi'

function retryable(error: unknown): boolean {
    return error instanceof ApiError && ((error.status >= 500 && error.status <= 599)
        || (error.status === 0 && ['NETWORK_ERROR', 'REQUEST_TIMEOUT'].includes(error.errorCode ?? '')))
}

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
            let failures = 0
            while (!signal.aborted && (next.state === 'QUEUED' || next.state === 'RUNNING')) {
                if (++polls > 450) throw new Error('Срок ожидания истёк')
                await new Promise<void>(resolve => window.setTimeout(resolve, failures ? 5000 : 2000))
                if (signal.aborted) return
                try {
                    next = await readUsageExport(next.id, signal)
                    failures = 0
                    update(next)
                } catch (error) {
                    if (signal.aborted) return
                    if (!retryable(error) || ++failures > 30) throw error
                    update(next, 'Связь с сервером прервана. Проверяем то же задание повторно.')
                }
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
        } catch (error) {
            if (!controller.signal.aborted) setResult({key: scopeKey, job: retryable(error) ? job : null,
                error: retryable(error) ? 'Связь с сервером прервана. Повторите скачивание.'
                    : 'Файл недоступен или срок хранения истёк. Создайте новый экспорт.', busy: false})
        }
    }
    return {run, download, pending, ready: job?.state === 'READY', error: current?.error ?? ''}
}

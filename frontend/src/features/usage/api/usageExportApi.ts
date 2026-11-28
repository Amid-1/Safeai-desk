// frontend/src/features/usage/api/usageExportApi.ts
import { apiRequest, API_TIMEOUTS } from '../../../shared/api/http'
import { uuidPathSegment } from '../../../shared/api/query'
import { expectRecord, expectInstant, contractError } from '../../../shared/api/runtime'
import { toUtcStartOfDayIso, toUtcExclusiveEndOfDayIso, assertDateRange } from '../../../shared/lib/date'

export type ExportFilter = {
    kind: string
    dateFrom: string
    dateTo: string
    organizationId: string | null
    userId?: string
    model?: string
    order?: string
    budgetStatus?: string | null
}

export type ExportJob = {
    id: string
    state: 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED'
    expiresAt: string
    failure: string | null
}

export function parseExportJob(value: unknown): ExportJob {
    const row = expectRecord(value, 'exportJob')
    if (typeof row.id !== 'string' || typeof row.state !== 'string'
        || !['QUEUED', 'RUNNING', 'READY', 'FAILED'].includes(row.state)) {
        throw contractError('Некорректное состояние экспорта')
    }
    const failure = typeof row.failure === 'string' ? row.failure : null
    if ((row.state === 'FAILED') !== (failure !== null)) throw contractError('Некорректный результат экспорта')
    return {id: uuidPathSegment(row.id), state: row.state as ExportJob['state'],
        expiresAt: expectInstant(row.expiresAt, 'expiresAt'), failure}
}

export async function startUsageExport(filter: ExportFilter, signal: AbortSignal): Promise<ExportJob> {
    assertDateRange(filter.dateFrom, filter.dateTo, 366)
    return parseExportJob(await apiRequest<unknown>('/api/admin/usage/exports', {
        method: 'POST', signal, json: {
            ...filter, kind: filter.kind.toUpperCase(),
            dateFrom: toUtcStartOfDayIso(filter.dateFrom), dateTo: toUtcExclusiveEndOfDayIso(filter.dateTo),
            userId: filter.userId || null, model: filter.model || null, budgetStatus: filter.budgetStatus || null,
        },
    }))
}

export async function readUsageExport(id: string, signal: AbortSignal): Promise<ExportJob> {
    const result = parseExportJob(await apiRequest<unknown>(
        `/api/admin/usage/exports/${uuidPathSegment(id)}`, {signal},
    ))
    if (result.id !== id) throw contractError('Сервер вернул другое задание')
    return result
}

export async function downloadUsageExport(id: string, signal: AbortSignal): Promise<void> {
    const blob = await apiRequest<Blob>(`/api/admin/usage/exports/${uuidPathSegment(id)}/download`, {
        signal, responseType: 'blob', timeoutMs: API_TIMEOUTS.report,
    })
    if (signal.aborted) return
    if (blob.size > 8 * 1024 * 1024 || !blob.type.startsWith('text/csv')) {
        throw contractError('Некорректный файл экспорта')
    }
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'safeai-usage.csv'
    document.body.appendChild(link)
    try { link.click() } finally {
        link.remove()
        window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
}

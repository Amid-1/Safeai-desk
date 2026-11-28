// frontend/src/features/usage/controller/usePhysicalUsageReport.ts
import { useEffect, useState } from 'react'
import { getPhysicalUsageReport, type PhysicalUsageReport } from '../api/physicalUsageApi'

export function usePhysicalUsageReport(
    enabled: boolean, dateFrom: string, dateTo: string, organizationId: string | null,
    superAdmin: boolean, userId: string, revision: number,
) {
    const key = JSON.stringify([dateFrom, dateTo, organizationId, superAdmin, userId, revision])
    const [result, setResult] = useState<{
        key: string; report: PhysicalUsageReport | null; error: boolean
    } | null>(null)

    useEffect(() => {
        if (!enabled) return
        const controller = new AbortController()
        void getPhysicalUsageReport({
            dateFrom, dateTo, organizationId: superAdmin ? organizationId : null,
            expectedOrganizationId: organizationId,
        }, controller.signal).then(report => {
            if (!controller.signal.aborted) setResult({key, report, error: false})
        }).catch(() => {
            if (!controller.signal.aborted) setResult({key, report: null, error: true})
        })
        return () => controller.abort()
    }, [key, enabled, dateFrom, dateTo, organizationId, superAdmin])

    const current = enabled && result?.key === key ? result : null
    return {report: current?.report ?? null, error: current?.error ?? false, loading: enabled && !current}
}

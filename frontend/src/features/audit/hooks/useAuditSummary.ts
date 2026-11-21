// frontend/src/features/audit/hooks/useAuditSummary.ts
import { useEffect, useState } from 'react'
import { getAuditSummary } from '../api/adminAuditApi'
import type { AuditEventFilter, AuditSummary } from '../api/adminAuditApi'
import { getApiErrorMessage } from '../../../shared/api/http'

export function useAuditSummary(authScope: string, filter: AuditEventFilter, reloadToken: number) {
    const filterKey = JSON.stringify({ ...filter, metric: undefined })
    const key = JSON.stringify([authScope, filterKey, reloadToken])
    const [state, setState] = useState<{ key: string; data: AuditSummary | null; error: string }>({
        key: '', data: null, error: '',
    })
    useEffect(() => {
        const controller = new AbortController()
        let active = true
        async function load() {
            try {
                const data = await getAuditSummary(JSON.parse(filterKey) as AuditEventFilter, { signal: controller.signal })
                if (active) setState({ key, data, error: '' })
            } catch (error) {
                if (active) setState({ key, data: null, error: getApiErrorMessage(error, 'Не удалось загрузить сводку аудита.') })
            }
        }
        void load()
        return () => { active = false; controller.abort() }
    }, [key, filterKey])
    return {
        summary: state.key === key ? state.data : null,
        summaryError: state.key === key ? state.error : '',
        summaryLoading: state.key !== key,
    }
}

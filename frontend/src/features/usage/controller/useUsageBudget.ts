// frontend/src/features/usage/controller/useUsageBudget.ts
import { useEffect, useState } from 'react'
import { getOrganizationBudgetStatus, type OrganizationBudgetStatus } from '../api/usage/usageBudget'

export function useUsageBudget(userId: string, organizationId: string, reloadToken: number) {
    const key = JSON.stringify([userId, organizationId, reloadToken])
    const [state, setState] = useState<{ key: string; policy: OrganizationBudgetStatus | null; error: boolean }>({
        key: '', policy: null, error: false,
    })
    useEffect(() => {
        if (!userId || !organizationId) return
        const controller = new AbortController()
        getOrganizationBudgetStatus(organizationId, controller.signal).then(policy => {
            if (!controller.signal.aborted) {
                setState({ key, policy: policy.organizationId === organizationId ? policy : null,
                    error: policy.organizationId !== organizationId })
            }
        }).catch(() => {
            if (!controller.signal.aborted) setState({ key, policy: null, error: true })
        })
        return () => controller.abort()
    }, [key, userId, organizationId])
    return {
        scoped: Boolean(userId && organizationId),
        loading: Boolean(userId && organizationId) && state.key !== key,
        policy: state.key === key ? state.policy : null,
        error: state.key === key && state.error,
    }
}

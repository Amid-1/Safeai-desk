// frontend/src/features/usage/controller/useUsageBudget.ts
import { useEffect, useState } from 'react'
import { getOrganizationBudgetStatus, type OrganizationBudgetStatus } from '../api/usage/usageBudget'
import { ApiError } from '../../../shared/api/httpErrors'

function budgetErrorMessage(error: unknown): string {
    if (error instanceof ApiError) {
        if (error.errorCode === 'INVALID_RESPONSE') return 'Сервер вернул некорректные сведения о бюджете.'
        if (error.status === 401 || error.status === 403) return 'Нет доступа к бюджету организации.'
        if (error.status === 404) return 'Сервер не предоставил бюджет организации.'
        if (error.status >= 500) return 'Ошибка сервера при расчёте бюджета.'
    }
    return 'Не удалось получить сведения о бюджете.'
}

export function useUsageBudget(userId: string, organizationId: string, reloadToken: number, retryToken = 0) {
    const key = JSON.stringify([userId, organizationId, reloadToken, retryToken])
    const [state, setState] = useState<{ key: string; policy: OrganizationBudgetStatus | null; error: string }>({
        key: '', policy: null, error: '',
    })
    useEffect(() => {
        if (!userId || !organizationId) return
        const controller = new AbortController()
        getOrganizationBudgetStatus(organizationId, controller.signal).then(policy => {
            if (!controller.signal.aborted) {
                setState({ key, policy: policy.organizationId === organizationId ? policy : null,
                    error: policy.organizationId !== organizationId ? 'Сервер вернул некорректные сведения о бюджете.' : '' })
            }
        }).catch(error => {
            if (!controller.signal.aborted) setState({ key, policy: null, error: budgetErrorMessage(error) })
        })
        return () => controller.abort()
    }, [key, userId, organizationId])
    return {
        scoped: Boolean(userId && organizationId),
        loading: Boolean(userId && organizationId) && state.key !== key,
        policy: state.key === key ? state.policy : null,
        error: state.key === key && Boolean(state.error),
        errorMessage: state.key === key ? state.error : '',
    }
}

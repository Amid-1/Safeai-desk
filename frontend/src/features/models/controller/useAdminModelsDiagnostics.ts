// frontend/src/features/models/controller/useAdminModelsDiagnostics.ts
// ============================================================
// frontend/src/features/models/controller/useAdminModelsDiagnostics.ts
// ============================================================
import { useCallback, useEffect, useRef, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { getModelRouteDecision, probeRuntimeModel } from '../../../entities/model/api/modelApi'
import type { ModelRouteDecision, RuntimeModelProbe } from '../../../entities/model/api/modelApi'
import { getApiErrorMessage } from '../../../shared/api/http'
import { MODEL_DECISION_ID_PATTERN } from '../../../shared/lib/modelRouteDenial'

export function useAdminModelsDiagnostics(
    isSuperAdmin: boolean,
    setError: Dispatch<SetStateAction<string>>,
) {
    const [routeDecisionId, setRouteDecisionId] =
        useState('')

    const [routeDecision, setRouteDecision] =
        useState<ModelRouteDecision | null>(
            null,
        )

    const [routePending, setRoutePending] =
        useState(false)

    const [routeError, setRouteError] =
        useState('')

    const [runtimeProbe, setRuntimeProbe] =
        useState<RuntimeModelProbe | null>(
            null,
        )

    const [
        runtimeProbePending,
        setRuntimeProbePending,
    ] = useState(false)

    const handleRuntimeProbe =
        useCallback(async () => {
            if (!isSuperAdmin) {
                return
            }

            setRuntimeProbePending(true)
            setError('')

            try {
                const result =
                    await probeRuntimeModel()

                setRuntimeProbe(result)
            } catch (failure) {
                setError(
                    getApiErrorMessage(
                        failure,
                        'Не удалось проверить доступность runtime-модели.',
                    ),
                )
            } finally {
                setRuntimeProbePending(false)
            }
        }, [isSuperAdmin, setError])

    const routeControllerRef = useRef<AbortController | null>(null)
    useEffect(() => () => routeControllerRef.current?.abort(), [])

    const handleRouteDecisionIdChange = useCallback((value: string) => {
        routeControllerRef.current?.abort()
        setRouteDecisionId(value)
        setRouteDecision(null)
        setRouteError('')
        setRoutePending(false)
    }, [])

    const lookupRouteDecision =
        useCallback(async (value: string) => {
            const decisionId = value.trim()
            routeControllerRef.current?.abort()
            setRouteDecisionId(value)

            setRouteDecision(null)
            setRouteError('')

            if (!MODEL_DECISION_ID_PATTERN.test(decisionId)) {
                setRoutePending(false)
                setRouteError(
                    'Введите номер решения из журнала событий, а не идентификатор пользователя.',
                )
                return
            }

            routeControllerRef.current?.abort()
            const controller = new AbortController()
            routeControllerRef.current = controller
            setRoutePending(true)

            try {
                const result = await getModelRouteDecision(
                    decisionId,
                    { signal: controller.signal },
                )
                if (!controller.signal.aborted) {
                    setRouteDecision(result)
                }
            } catch (failure) {
                if (!controller.signal.aborted) {
                    setRouteError(getApiErrorMessage(
                        failure,
                        'Решение маршрутизации не найдено.',
                    ))
                }
            } finally {
                if (routeControllerRef.current === controller) {
                    routeControllerRef.current = null
                    setRoutePending(false)
                }
            }
        }, [])
    const handleRouteLookup = useCallback(() => lookupRouteDecision(routeDecisionId),
        [lookupRouteDecision, routeDecisionId])

    return {
        routeDecisionId,
        handleRouteDecisionIdChange,
        routeDecision,
        routePending,
        routeError,
        runtimeProbe,
        runtimeProbePending,
        handleRuntimeProbe,
        handleRouteLookup,
        lookupRouteDecision,
    }
}

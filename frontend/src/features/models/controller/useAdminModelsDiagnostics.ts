// frontend/src/features/models/controller/useAdminModelsDiagnostics.ts
// ============================================================
// frontend/src/features/models/controller/useAdminModelsDiagnostics.ts
// ============================================================
import { useCallback, useEffect, useRef, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { getModelRouteDecision, probeRuntimeModel } from '../../../entities/model/api/modelApi'
import type { ModelRouteDecision, RuntimeModelProbe } from '../../../entities/model/api/modelApi'
import { getApiErrorMessage } from '../../../shared/api/http'

const UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

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
        }, [isSuperAdmin])

    const routeControllerRef = useRef<AbortController | null>(null)
    useEffect(() => () => routeControllerRef.current?.abort(), [])

    const handleRouteDecisionIdChange = useCallback((value: string) => {
        routeControllerRef.current?.abort()
        setRouteDecisionId(value)
        setRouteDecision(null)
        setRouteError('')
        setRoutePending(false)
    }, [])

    const handleRouteLookup =
        useCallback(async () => {
            const decisionId =
                routeDecisionId.trim()

            setRouteDecision(null)
            setRouteError('')

            if (!UUID_PATTERN.test(decisionId)) {
                setRouteError(
                    'Введите корректный ID решения (UUID).',
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
        }, [routeDecisionId])

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
    }
}

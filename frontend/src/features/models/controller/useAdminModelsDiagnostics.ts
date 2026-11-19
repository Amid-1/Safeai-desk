// frontend/src/features/models/controller/useAdminModelsDiagnostics.ts
// ============================================================
// frontend/src/features/models/controller/useAdminModelsDiagnostics.ts
// ============================================================
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { getModelRouteDecision, probeRuntimeModel } from '../../../entities/model/api/modelApi'
import type { ModelRouteDecision, RuntimeModelProbe, RuntimeModelStatus } from '../../../entities/model/api/modelApi'
import { getApiErrorMessage } from '../../../shared/api/http'
import { MODEL_DECISION_ID_PATTERN } from '../../../shared/lib/modelRouteDenial'

export function useAdminModelsDiagnostics(
    isSuperAdmin: boolean,
    setError: Dispatch<SetStateAction<string>>,
    runtime: RuntimeModelStatus | null,
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

    const [probeResult, setRuntimeProbe] =
        useState<{context: string; result: RuntimeModelProbe} | null>(
            null,
        )
    const [probeAttemptContext, setProbeAttemptContext] = useState<string | null>(null)

    const [
        runtimeProbePending,
        setRuntimeProbePending,
    ] = useState(false)

    const probeContext = JSON.stringify(runtime)
    const probeControllerRef = useRef<AbortController | null>(null)
    useLayoutEffect(() => {
        setRuntimeProbe(null)
        setProbeAttemptContext(null)
        setRuntimeProbePending(false)
        return () => {
            probeControllerRef.current?.abort()
            probeControllerRef.current = null
        }
    }, [probeContext, isSuperAdmin])
    const runtimeProbe = isSuperAdmin && probeResult?.context === probeContext ? probeResult.result : null
    const handleRuntimeProbe =
        useCallback(async () => {
            if (!isSuperAdmin || !runtime || !runtime.enabled || probeControllerRef.current) {
                return
            }

            const controller = new AbortController()
            probeControllerRef.current = controller
            setProbeAttemptContext(probeContext)
            setRuntimeProbe(null)
            setRuntimeProbePending(true)
            setError('')

            try {
                const result =
                    await probeRuntimeModel({signal: controller.signal})

                if (controller.signal.aborted) return
                if (result.provider !== runtime.provider || result.model !== runtime.model) {
                    setError('Подключение изменилось. Обновите страницу и выполните проверку заново.')
                    return
                }
                setRuntimeProbe({context: probeContext, result})
            } catch {
                if (controller.signal.aborted) return
                setError('Не удалось проверить доступность подключённой модели.')
            } finally {
                if (probeControllerRef.current === controller) {
                    probeControllerRef.current = null
                    setRuntimeProbePending(false)
                }
            }
        }, [isSuperAdmin, setError, runtime, probeContext])

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
        runtimeHealthUnconfirmed: isSuperAdmin && probeAttemptContext === probeContext && !runtimeProbe,
        runtimeProbePending,
        handleRuntimeProbe,
        handleRouteLookup,
        lookupRouteDecision,
    }
}

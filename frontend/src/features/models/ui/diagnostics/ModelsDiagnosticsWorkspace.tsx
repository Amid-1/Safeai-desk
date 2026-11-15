// frontend/src/features/models/ui/diagnostics/ModelsDiagnosticsWorkspace.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { RuntimeCard, RouteDecisionEvidence } from '../ModelControlPlaneViews'
import { ErrorState } from '../../../../shared/ui/StateBlock'
import { useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'

export function ModelsDiagnosticsWorkspace({ controller }: { controller: AdminModelsController }) {
    const { runtime, effectiveCatalog, runtimeProbe, runtimeProbePending, isSuperAdmin,
        handleRuntimeProbe, routeDecisionId, handleRouteDecisionIdChange,
        routeError, routePending, routeDecision, handleRouteLookup, lookupRouteDecision } = controller
    const [params] = useSearchParams()
    const linkedDecisionId = params.get('decisionId')
    const decisionSectionRef = useRef<HTMLDivElement>(null)
    useEffect(() => {
        if (linkedDecisionId) void lookupRouteDecision(linkedDecisionId)
    }, [linkedDecisionId, lookupRouteDecision])
    useEffect(() => {
        if (linkedDecisionId && routeDecisionId.trim().toLowerCase() === linkedDecisionId.toLowerCase()
            && (routeDecision || routeError)) {
            decisionSectionRef.current?.scrollIntoView?.({ block: 'start' })
        }
    }, [linkedDecisionId, routeDecisionId, routeDecision, routeError])
    if (!runtime) return null
    return (
        <section className="models-section-stack" aria-labelledby="models-diagnostics-title">
            <header className="models-section-heading">
                <span className="models-eyebrow">ТЕХНИЧЕСКИЕ ПРОВЕРКИ</span>
                <h2 id="models-diagnostics-title">Проверки и диагностика</h2>
                <p>Проверьте текущее подключение модели или найдите сохранённое решение по конкретному запросу.</p>
            </header>
            <div className="models-diagnostics-runtime">
                <p className="models-diagnostics-context">Текущая конфигурация модели. Она может отличаться от настроек на момент исторического запроса.</p>
                <RuntimeCard runtime={runtime} effectiveCatalog={effectiveCatalog}
                    probe={runtimeProbe} probePending={runtimeProbePending}
                    canProbe={isSuperAdmin} onProbe={() => { void handleRuntimeProbe() }} />
            </div>
            <div className="models-diagnostics-decision" ref={decisionSectionRef}>
                <h3>Проверка решения по запросу</h3>
                <p>Откройте диагностику из события в журнале или введите номер решения безопасности. Идентификатор пользователя для этой проверки не подходит.</p>
                <form className="models-evidence-search" onSubmit={(event) => {
                    event.preventDefault(); void handleRouteLookup()
                }}>
                    <label htmlFor="models-route-decision-id">Номер решения безопасности</label>
                    <input id="models-route-decision-id" type="text" autoComplete="off" inputMode="text" spellCheck={false}
                        value={routeDecisionId} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                        onChange={(event) => handleRouteDecisionIdChange(event.target.value)} />
                    <button type="submit" disabled={routePending}>
                        {routePending ? 'Проверяем...' : 'Проверить'}
                    </button>
                </form>
                {routeError && <ErrorState message={routeError} variant="inline" />}
                {routeDecision && <RouteDecisionEvidence key={routeDecision.id} decision={routeDecision} />}
            </div>
        </section>
    )
}

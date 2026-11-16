// frontend/src/features/models/ui/diagnostics/ModelsDiagnosticsWorkspace.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { RuntimeCard, RouteDecisionEvidence } from '../ModelControlPlaneViews'
import { ErrorState } from '../../../../shared/ui/StateBlock'
import { useEffect, useRef } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ModelIcon } from '../ModelIcon'

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
            // Models uses the document scroll on this route. Native scrolling also
            // handles a nested scroll container if the shell layout changes.
            decisionSectionRef.current?.scrollIntoView?.({ block: 'start', inline: 'nearest', behavior: 'instant' })
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
                <p className="models-diagnostics-context">Текущая конфигурация сервера — не снимок настроек на момент исторического запроса.</p>
                <RuntimeCard runtime={runtime} effectiveCatalog={effectiveCatalog}
                    probe={runtimeProbe} probePending={runtimeProbePending}
                    canProbe={isSuperAdmin} onProbe={() => { void handleRuntimeProbe() }} />
            </div>
            <div className="models-diagnostics-decision" ref={decisionSectionRef}>
                <span className="models-eyebrow">ИСТОРИЯ РЕШЕНИЙ</span>
                <div className="models-decision-heading"><h3><ModelIcon name="history" />Диагностика запроса</h3>
                    <Link className="models-audit-link" to="/admin/audit">Открыть журнал аудита <ModelIcon name="arrow" /></Link></div>
                <p>Найдите сохранённое решение сервера по конкретному запросу: какая модель была выбрана или почему запрос был отклонён. Новая проверка не запускается.</p>
                <form className="models-evidence-search" onSubmit={(event) => {
                    event.preventDefault(); void handleRouteLookup()
                }}>
                    <label htmlFor="models-route-decision-id">Идентификатор решения безопасности (UUID)</label>
                    <input id="models-route-decision-id" type="text" autoComplete="off" inputMode="text" spellCheck={false}
                        aria-describedby="models-decision-id-hint"
                        value={routeDecisionId} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                        onChange={(event) => handleRouteDecisionIdChange(event.target.value)} />
                    <button className="btn-primary" type="submit" disabled={routePending || !routeDecisionId.trim()}>
                        <ModelIcon name="search" />{routePending ? 'Поиск...' : 'Найти решение'}
                    </button>
                    <small id="models-decision-id-hint">UUID находится в деталях события маршрутизации в журнале аудита. Оттуда можно сразу открыть диагностику.</small>
                </form>
                {routeError && <ErrorState message={routeError} variant="inline" />}
                {routeDecision && <RouteDecisionEvidence key={routeDecision.id} decision={routeDecision} />}
            </div>
        </section>
    )
}

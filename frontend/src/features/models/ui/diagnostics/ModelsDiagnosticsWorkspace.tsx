// frontend/src/features/models/ui/diagnostics/ModelsDiagnosticsWorkspace.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { RuntimeCard, RouteDecisionEvidence } from '../ModelControlPlaneViews'
import { ErrorState } from '../../../../shared/ui/StateBlock'

export function ModelsDiagnosticsWorkspace({ controller }: { controller: AdminModelsController }) {
    const { runtime, effectiveCatalog, runtimeProbe, runtimeProbePending, isSuperAdmin,
        handleRuntimeProbe, routeDecisionId, handleRouteDecisionIdChange,
        routeError, routePending, routeDecision, handleRouteLookup } = controller
    if (!runtime) return null
    return (
        <section className="models-section-stack" aria-labelledby="models-diagnostics-title">
            <header className="models-section-heading">
                <span className="models-eyebrow">ТЕХНИЧЕСКИЕ ПРОВЕРКИ</span>
                <h2 id="models-diagnostics-title">Проверки и диагностика</h2>
                <p>Проверка соединения подтверждает доступность провайдера, но не разрешение конкретного AI-запроса. Проверка решения использует ID из аудита.</p>
            </header>
            <div className="models-diagnostics-runtime">
                <RuntimeCard runtime={runtime} effectiveCatalog={effectiveCatalog}
                    probe={runtimeProbe} probePending={runtimeProbePending}
                    canProbe={isSuperAdmin} onProbe={() => { void handleRuntimeProbe() }} />
            </div>
            <div className="models-diagnostics-decision">
                <h3>Диагностика маршрутизации</h3>
                <p>Введите ID решения из журнала аудита или сообщения об ошибке, чтобы узнать, почему запрос был разрешён или отклонён.</p>
                <form className="models-evidence-search" onSubmit={(event) => {
                    event.preventDefault(); void handleRouteLookup()
                }}>
                    <label htmlFor="models-route-decision-id">ID решения</label>
                    <input id="models-route-decision-id" type="text" autoComplete="off"
                        value={routeDecisionId} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                        onChange={(event) => handleRouteDecisionIdChange(event.target.value)} />
                    <button type="submit" disabled={routePending}>
                        {routePending ? 'Проверяем...' : 'Проверить'}
                    </button>
                </form>
                {routeError && <ErrorState message={routeError} variant="inline" />}
                {routeDecision && <RouteDecisionEvidence decision={routeDecision} />}
            </div>
        </section>
    )
}

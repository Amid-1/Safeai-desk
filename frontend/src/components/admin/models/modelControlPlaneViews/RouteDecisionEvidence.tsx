// frontend/src/components/admin/models/modelControlPlaneViews/RouteDecisionEvidence.tsx
// Назначение: отображение доказательств решения маршрутизации.

import type { ModelRouteDecision } from '../../../../api/modelApi'
import {formatDateTime, formatUsd} from '../../../../utils/format'
import {
    StatusPill,
    routeReasonLabel,
    monthlyCostStateLabel,
    budgetEnforcementLabel,
} from './viewPresentation'

export function RouteDecisionEvidence({
    decision,
}: {
    decision: ModelRouteDecision
}) {
    return (
        <section className="models-route-evidence">
            <div className="models-route-evidence__heading">
                <div>
                    <strong>{decision.id}</strong>
                    <small>{formatDateTime(decision.createdAt)}</small>
                </div>

                <StatusPill
                    tone={decision.outcome === 'ALLOWED' ? 'success' : 'danger'}
                    label={`${decision.outcome === 'ALLOWED' ? 'Разрешено' : 'Отклонено'} · ${routeReasonLabel(decision.reason)}`}
                />
            </div>

            <dl className="models-kv-grid">
                <div>
                    <dt>Выбранная модель</dt>
                    <dd>{decision.selectedModelKey ?? '—'}</dd>
                    <small>
                        {decision.selectedProvider ?? '—'}
                        {' / '}
                        {decision.selectedProviderModelId ?? '—'}
                    </small>
                </div>

                <div>
                    <dt>Версии настроек</dt>
                    <dd>
                        {decision.selectedCatalogVersion !== null
                            ? `Каталог ${decision.selectedCatalogVersion}`
                            : 'Каталог —'}
                        {' · '}
                        {decision.policyVersion !== null
                            ? `Правила ${decision.policyVersion}`
                            : 'Правила —'}
                    </dd>
                </div>

                <div>
                    <dt>Оценка входа / выхода</dt>
                    <dd>
                        {decision.estimatedInputTokens?.toLocaleString('ru-RU') ?? '—'}
                        {' / '}
                        {decision.estimatedOutputTokens?.toLocaleString('ru-RU') ?? '—'}
                    </dd>
                    <small>
                        Исторические поля API и БД сохраняют технические имена *_tokens
                    </small>
                </div>

                <div>
                    <dt>Оценка стоимости</dt>
                    <dd>
                        {decision.estimatedMaxCostUsd === null
                            ? 'Неизвестно'
                            : formatUsd(decision.estimatedMaxCostUsd)}
                    </dd>
                    <small>
                        Полнота стоимости: {decision.pricingComplete ? 'да' : 'нет'}
                    </small>
                </div>

                <div>
                    <dt>Месячный бюджет</dt>
                    <dd>
                        {decision.monthlyBudgetUsd === null
                            ? 'Не применялся'
                            : formatUsd(decision.monthlyBudgetUsd)}
                    </dd>
                    <small>
                        {monthlyCostStateLabel(decision.monthlyCostState)}
                        {' · '}
                        {budgetEnforcementLabel(decision.budgetEnforcement)}
                    </small>
                </div>

                <div>
                    <dt>Причина</dt>
                    <dd>{routeReasonLabel(decision.reason)}</dd>
                    <small>{decision.reason}</small>
                </div>
            </dl>

            <div className="models-route-accounting">
                <h3>Доказательства маршрутизации V48</h3>

                <div className="models-route-accounting__grid">
                    <div>
                        <span>Версия целостности</span>
                        <strong>
                            v{decision.decisionIntegrityVersion}
                        </strong>
                        <small>
                            Версия схемы доказательств и контрольного хэша,
                            а не версия AI-модели.
                        </small>
                    </div>

                    <div>
                        <span>Учёт входа</span>
                        <strong>
                            {decision.inputAccountingVersion
                                ?? 'Историческое решение V1/V2'}
                        </strong>
                        <small>
                            Версия алгоритма учёта входа.
                            Это не токенизатор провайдера.
                        </small>
                    </div>

                    <div>
                        <span>Резерв дополнительного входа</span>
                        <strong>
                            {decision.additionalInputUnitUpperBound ?? '—'}
                        </strong>
                        <small>
                            Верхняя граница дополнительного системного входа, контекста базы знаний
                            и описаний инструментов до обращения к провайдеру.
                        </small>
                    </div>
                </div>

                <code className="models-route-evidence__hash">
                    SHA-256: {decision.decisionSha256}
                </code>

                <p>
                    Для нового решения V48 ожидается версия целостности v3,
                    указанная версия учёта, неотрицательная граница входа
                    и 64-символьный SHA-256 в нижнем регистре. Исторические
                    решения V1/V2 не переписываются.
                </p>
            </div>
        </section>
    )
}

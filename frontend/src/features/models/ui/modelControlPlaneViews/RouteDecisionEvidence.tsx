// frontend/src/features/models/ui/modelControlPlaneViews/RouteDecisionEvidence.tsx
// Назначение: отображение доказательств решения маршрутизации.

import { useState } from 'react'
import type { ModelRouteDecision } from '../../../../entities/model/api/modelApi'
import {formatDateTime, formatUsd} from '../../../../shared/lib/format'
import { describeModelRouteDenial } from '../../../../shared/lib/modelRouteDenial'
import { StatusPill } from './viewPresentation'
import { routeReasonLabel, monthlyCostStateLabel, budgetEnforcementLabel } from './viewPresentationLabels'

export function RouteDecisionEvidence({
    decision,
}: {
    decision: ModelRouteDecision
}) {
    const [copyStatus, setCopyStatus] = useState('')
    const allowed = decision.outcome === 'ALLOWED'
    const unconfigured = !allowed && decision.reason === 'MODEL_NOT_ALLOWED'
        && decision.policyId === null && decision.policyVersion === null
    const noEstimate = decision.estimatedInputTokens === null && decision.estimatedOutputTokens === null
    const reason = unconfigured ? 'Правила доступа к AI не были настроены.'
        : allowed ? routeReasonLabel(decision.reason) : describeModelRouteDenial(decision.reason)

    async function copyDecision() {
        try {
            await navigator.clipboard.writeText(decision.id)
            setCopyStatus('Номер решения скопирован')
        } catch {
            setCopyStatus('Не удалось скопировать. Выделите номер вручную.')
        }
    }

    return (
        <section className="models-route-evidence">
            <div className="models-route-evidence__heading">
                <div>
                    <h3>Результат проверки AI-запроса</h3>
                    <small>{formatDateTime(decision.createdAt)}</small>
                </div>

                <StatusPill
                    tone={allowed ? 'success' : 'danger'}
                    label={allowed ? 'Разрешён' : 'Отклонён'}
                />
            </div>

            <div className={'models-route-summary models-route-summary--' + (allowed ? 'allowed' : 'denied')}>
                <strong>{allowed ? 'Причина разрешения' : 'Причина блокировки'}</strong>
                <p>{reason}</p>
                {unconfigured && <small>На момент запроса организация не имела настроенной политики использования моделей. Проверьте текущие правила на вкладке «Доступ».</small>}
                {!allowed && !unconfigured && <small>Решение отражает условия на момент запроса. Для дальнейших запросов проверьте актуальные настройки доступа, каталога и лимитов.</small>}
                {allowed && <small>Разрешение на использование модели не подтверждает получение ответа от провайдера.</small>}
            </div>

            <dl className="models-kv-grid">
                <div>
                    <dt>Выбранная модель</dt>
                    <dd>{decision.selectedModelKey ?? 'Модель не выбрана'}</dd>
                    {(decision.selectedProvider || decision.selectedProviderModelId) && <small>
                        {[decision.selectedProvider, decision.selectedProviderModelId].filter(Boolean).join(' / ')}
                    </small>}
                </div>

                <div>
                    <dt>Оценка объёма входа / ответа</dt>
                    <dd>
                        {noEstimate ? 'Не выполнялась' : [decision.estimatedInputTokens, decision.estimatedOutputTokens]
                            .map(value => value?.toLocaleString('ru-RU') ?? 'Не рассчитано').join(' / ')}
                    </dd>
                    {!noEstimate && decision.inputAccountingVersion && <small>Вход — внутренние единицы учёта, не фактические токены провайдера.</small>}
                </div>

                <div>
                    <dt>Оценка стоимости</dt>
                    <dd>
                        {decision.estimatedMaxCostUsd === null
                            ? noEstimate && !allowed ? 'Не рассчитывалась' : 'Неизвестно'
                            : formatUsd(decision.estimatedMaxCostUsd)}
                    </dd>
                </div>

                <div>
                    <dt>Месячный бюджет</dt>
                    <dd>
                        {decision.monthlyCostState === 'NOT_EVALUATED' ? 'Проверка не выполнялась'
                            : decision.monthlyBudgetUsd === null ? 'Не применялся'
                            : formatUsd(decision.monthlyBudgetUsd)}
                    </dd>
                    {decision.monthlyCostState !== 'NOT_EVALUATED' && <small>
                        {monthlyCostStateLabel(decision.monthlyCostState)}
                        {' · '}
                        {budgetEnforcementLabel(decision.budgetEnforcement)}
                    </small>}
                </div>
            </dl>

            <div className="models-route-number">
                <strong>Номер решения безопасности</strong>
                <div><code>{decision.id}</code><button type="button" className="secondary-button" onClick={() => void copyDecision()}>Копировать номер решения</button></div>
                {copyStatus && <small role="status">{copyStatus}</small>}
            </div>

            <details className="models-route-accounting">
                <summary>Технические сведения решения</summary>

                <div className="models-route-accounting__grid">
                    <div>
                        <span>Код причины</span><strong>{decision.reason}</strong>
                    </div>
                    <div>
                        <span>Версии настроек</span>
                        <strong>Каталог: {decision.selectedCatalogVersion ?? 'Не зафиксирована'}</strong>
                        <strong>Правила: {decision.policyVersion ?? 'Не зафиксирована'}</strong>
                    </div>
                    <div>
                        <span>Полнота данных о стоимости</span><strong>{decision.pricingComplete ? 'Да' : 'Нет'}</strong>
                        <small>Месячный лимит: {decision.monthlyBudgetUsd === null ? 'Не указан' : formatUsd(decision.monthlyBudgetUsd)}</small>
                        <small>{monthlyCostStateLabel(decision.monthlyCostState)} · {budgetEnforcementLabel(decision.budgetEnforcement)}</small>
                    </div>
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
                                ?? 'Не зафиксирован в историческом решении'}
                        </strong>
                        <small>
                            Версия алгоритма учёта входа.
                            Это не токенизатор провайдера.
                        </small>
                    </div>

                    <div>
                        <span>Резерв дополнительного входа</span>
                        <strong>
                            {decision.additionalInputUnitUpperBound ?? 'Не зафиксирован'}
                        </strong>
                        <small>
                            Верхняя граница дополнительных входных данных до обращения к провайдеру.
                            Это внутренние единицы учёта, а не израсходованные токены.
                        </small>
                    </div>
                </div>

                <code className="models-route-evidence__hash">
                    SHA-256: {decision.decisionSha256}
                </code>

                <p>Контрольный хэш используется сервером для проверки целостности сохранённого решения.</p>
            </details>
        </section>
    )
}

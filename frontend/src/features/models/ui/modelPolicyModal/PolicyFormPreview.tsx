// frontend/src/features/models/ui/modelPolicyModal/PolicyFormPreview.tsx
// Серверная оценка черновика: состояния, ограничения и осознанная блокировка.

import type {ModelPolicyModalController} from './useModelPolicyModalController'
import type {ModelPolicyModalProps} from './policyModalTypes'
import {ErrorState} from '../../../../shared/ui/StateBlock'
import { previewReasonLabel } from './policyModalLabels'
import {POLICY_PREVIEW_LABEL, policyPreviewState} from './policyPreviewStatus'
import {formatUsd} from '../../../../shared/lib/format'

type Props = {control: ModelPolicyModalController; effectiveCatalog: ModelPolicyModalProps['effectiveCatalog']}

const CHECKS = [
    ['Доступ к моделям', 'Разрешения и запреты'],
    ['Совместимость', 'Подключение и каталог'],
    ['Стоимость и лимиты', 'Бюджет, токены и тарифы'],
    ['Требования к данным', 'Обучение и хранение'],
] as const

function usd(value: string | null): string {
    return value === null ? 'Не рассчитано' : formatUsd(value)
}

export function PolicyFormPreview({control, effectiveCatalog}: Props) {
    const {activationWarning, previewError, policyPreview, currentPreview,
        lockoutAcknowledged, setLockoutAcknowledged, previewPending} = control

    const status = policyPreviewState({currentPreview, policyPreview, previewPending, previewError})
    const statusLabel = POLICY_PREVIEW_LABEL[status]

    return (
        <div className="models-policy-check">
            <div className="models-policy-check__intro">
                <div>
                    <strong>Проверка правил доступа</strong>
                    <p>Сервер оценит текущие настройки и покажет, какие модели доступны и почему отдельные варианты могут быть отклонены.
                        Проверка не отправляет пользовательский запрос нейросети.</p>
                </div>
                <span className={`models-policy-check__status models-policy-check__status--${status}`} role="status">{statusLabel}</span>
            </div>

            <details key={currentPreview ? 'completed' : 'pending'} open={!currentPreview} className="models-policy-check__criteria" aria-label="Параметры проверки">
                <summary>{currentPreview ? 'Параметры выполненной проверки' : 'Что будет проверено'}</summary>
                <div className="models-policy-check__criteria-grid">
                    {CHECKS.map(([title, description]) => (
                        <div key={title} className="models-policy-check__criterion">
                            <strong>{title}</strong>
                            <small>{description}</small>
                        </div>
                    ))}
                </div>
            </details>

            {activationWarning && (
                <div className="models-policy-form__warning" role="alert">
                    <strong>Сейчас нет действующей записи каталога, совпадающей с подключённой моделью.</strong>
                    <p>Если сохранить правила включёнными, запросы этой организации будут
                        отклоняться до обращения к провайдеру. Перед сохранением выполните серверную проверку черновика.</p>
                </div>
            )}

            {previewError && <ErrorState message={previewError} variant="inline" />}

            {policyPreview && !currentPreview && (
                <p className="models-policy-form__preview-stale" role="status">
                    Правила изменены после предыдущей проверки либо изменилась конфигурация моделей.
                    Запустите «Проверить правила» заново перед сохранением.
                </p>
            )}

            {!currentPreview && !previewPending && (
                <div className="models-policy-check__empty">
                    <strong>{policyPreview ? 'Результат проверки устарел' : previewError ? 'Не удалось проверить правила' : 'Проверка ещё не выполнена'}</strong>
                    <p>Нажмите «Проверить правила» внизу окна, чтобы увидеть доступные модели, ограничения и решение сервера.</p>
                </div>
            )}

            {previewPending && (
                <p className="models-policy-check__pending" role="status">Проверяем правила доступа на сервере…</p>
            )}

            {currentPreview && (
                <section className={'models-policy-form__preview ' +
                    (status === 'blocked'
                        ? 'models-policy-form__preview--danger'
                        : status === 'restricted'
                            ? 'models-policy-form__preview--warning'
                            : 'models-policy-form__preview--ok')}
                         aria-label="Результат серверной проверки" aria-live="polite">
                    <div className="models-policy-form__preview-summary">
                        <div>
                            <strong>Результат проверки правил</strong>
                            <small>
                                Подключение: {currentPreview.runtimeProvider}/{currentPreview.runtimeModel}
                                {' · '}Доступных моделей: {currentPreview.executableModelCount}
                                {' · '}Проверено сервером: {new Date(currentPreview.evaluatedAt).toLocaleString('ru-RU')}
                                {' · '}Подтверждение до: {new Date(currentPreview.confirmationExpiresAt).toLocaleTimeString('ru-RU')}
                            </small>
                        </div>
                        <span>
                            {currentPreview.automaticOutcome === 'ALLOWED' ? 'Автовыбор разрешён' : 'Автовыбор отклонён'}
                            {' · '}{previewReasonLabel(currentPreview.automaticReason)}
                        </span>
                    </div>

                    <div className="models-policy-form__preview-list" aria-label="Результаты проверки моделей">
                        {currentPreview.models.map(item => (
                            <article key={`${item.modelKey}:${item.catalogVersion}`}
                                     className="models-policy-form__preview-row models-policy-check__model-result">
                                <div className="models-policy-check__model-heading">
                                    <div className="models-policy-check__model-id">
                                        <code>{item.modelKey}</code>
                                        <small>Версия модели в каталоге: {item.catalogVersion}</small>
                                    </div>
                                    <span className={`models-policy-check__model-outcome models-policy-check__model-outcome--${item.outcome.toLowerCase()}`}>
                                        {item.outcome === 'ALLOWED' ? 'Разрешена при проверке правил' : 'Отклонена при проверке правил'}
                                    </span>
                                </div>
                                <div className="models-policy-check__model-reason">
                                    <strong>{item.reason ? 'Причина отклонения' : 'Результат проверки'}</strong>
                                    <span>{item.reason ? previewReasonLabel(item.reason) : 'Модель соответствует проверенным правилам.'}</span>
                                </div>
                                <div className="models-policy-check__model-facts">
                                    <div><small>Лимиты токенов · вход / выход</small>
                                        <strong>{item.effectiveInputLimit.toLocaleString('ru-RU')} / {item.effectiveOutputLimit.toLocaleString('ru-RU')}</strong></div>
                                    <div><small>Стоимость · сведения каталога</small>
                                        <strong>{item.pricingComplete ? 'Данные о стоимости полные' : 'Данные о стоимости неполные'}</strong></div>
                                    <div><small>Расчётная стоимость при максимальных лимитах</small>
                                        <strong>{!item.pricingComplete ? 'Не рассчитано полностью' : item.estimatedMaxCostUsd !== null
                                            && /^0+(?:\.0+)?$/.test(item.estimatedMaxCostUsd)
                                            && effectiveCatalog.some(entry => entry.modelKey === item.modelKey
                                                && entry.version === item.catalogVersion && entry.pricingStatus === 'FREE')
                                            ? 'Бесплатно · 0 USD' : usd(item.estimatedMaxCostUsd)}</strong></div>
                                </div>
                                {item.monthlyBudgetUsd !== null && <details className="models-policy-check__budget-details">
                                    <summary>Месячный бюджет · {item.budgetExceeded ? 'Превышение' : item.monthlyCostKnown ? 'Расчёт доступен' : 'Стоимость неизвестна'}</summary>
                                    <div className="models-policy-check__model-facts">
                                        <div><small>Установленный бюджет</small><strong>{usd(item.monthlyBudgetUsd)}</strong></div>
                                        <div><small>Прогноз после тестового запроса</small><strong>{item.monthlyCostKnown ? usd(item.monthlyProjectedUsd) : 'Не рассчитано'}</strong></div>
                                    </div>
                                    <p>Расчёт сервера на момент проверки; фактическая стоимость зависит от запроса.</p>
                                </details>}
                            </article>
                        ))}
                        {currentPreview.models.length === 0 && (
                            <p className="models-policy-check__no-models">В результате проверки нет моделей для отображения.</p>
                        )}
                    </div>

                    {currentPreview.wouldLockOutOrganization && (
                        <label className="models-policy-form__lockout-confirm">
                            <input type="checkbox" checked={lockoutAcknowledged}
                                   onChange={event => setLockoutAcknowledged(event.target.checked)}/>
                            <span>Подтверждаю осознанную блокировку всех исполняемых моделей этой организации.</span>
                        </label>
                    )}
                    <p className="models-policy-check__disclaimer">
                        Положительный результат предварительной оценки не гарантирует исполнение любого будущего запроса.
                        Итоговое решение сервер принимает для каждого запроса отдельно.
                    </p>
                </section>
            )}
        </div>
    )
}

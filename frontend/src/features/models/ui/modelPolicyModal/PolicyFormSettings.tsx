// frontend/src/features/models/ui/modelPolicyModal/PolicyFormSettings.tsx
// Контролируемые поля политики: разделение по назначению без изменений backend-контрактов.

import type {ModelPolicyModalController} from './useModelPolicyModalController'
import type {ModelPolicyModalProps} from './policyModalTypes'
import {DefaultModelSelector} from '../ModelPolicyModelSelectors'
import type {Ref} from 'react'
import type {PolicyNumericField} from '../modelControlPlane/policyNumericFields'
import {BUDGET_ENFORCEMENTS, rubToUsd} from '../../../../entities/model/api/modelApi'
import type {BudgetEnforcement} from '../../../../entities/model/api/modelApi'
import { InfoHint } from './policyModalPresentation'
import { budgetEnforcementLabel } from './policyModalLabels'
import {PolicyBudgetCurrency} from './PolicyBudgetCurrency'
import {parsePolicyMoney} from '../modelControlPlane/policyNumericFields'
import type {PolicyDraft} from '../modelControlPlane/modelTypes'
import {formatUsd} from '../../../../shared/lib/format'

function budgetUsdEquivalent(value: string, quote: PolicyDraft['rubQuote']): string | null {
    if (!quote) return null
    try { return rubToUsd(parsePolicyMoney(value, 'Сумма'), quote.rubPerUsd) }
    catch { return null }
}

type Props = {
    control: ModelPolicyModalController
    catalog: ModelPolicyModalProps['catalog']
    effectiveCatalog: ModelPolicyModalProps['effectiveCatalog']
    runtime: ModelPolicyModalProps['runtime']
    pending: ModelPolicyModalProps['pending']
    section: 'access' | 'limits' | 'budget'
}

function PolicyNumericInput({control, field, label, idSuffix, section, pending, placeholder,
    hint, runtimeLimit, inputRef}: {
    control: ModelPolicyModalController
    field: PolicyNumericField
    label: string
    idSuffix: string
    section: 'limits' | 'budget'
    pending: boolean
    placeholder: string
    hint: string
    runtimeLimit?: number
    inputRef?: Ref<HTMLInputElement>
}) {
    const id = `${control.formId}-${idSuffix}`
    const error = control.numericErrors[field]
    const value = control.draft[field]
    const equivalent = section === 'budget' ? budgetUsdEquivalent(value, control.draft.rubQuote) : null
    const aboveRuntime = runtimeLimit !== undefined && !error && !!value.trim()
        && Number(value) > runtimeLimit
    return (
        <div id={id} tabIndex={-1} data-policy-block={section}
             className={`models-policy-form__setting-field models-policy-form__setting-field--numeric models-policy-form__setting-field--${section}`}>
            <label htmlFor={`${id}-value`}>
                {label}
                <input ref={inputRef} id={`${id}-value`}
                       inputMode={section === 'limits' ? 'numeric' : 'decimal'}
                       aria-label={label}
                       aria-invalid={!!error}
                       aria-describedby={[
                           `${id}-hint`,
                           runtimeLimit !== undefined ? `${id}-runtime` : '',
                           error ? `${id}-error` : '',
                           aboveRuntime ? `${id}-warning` : '',
                       ].filter(Boolean).join(' ')}
                       placeholder={placeholder} value={value} disabled={pending}
                       onChange={event => {
                           const next = event.target.value
                           control.setDraft(current => ({...current, [field]: next}))
                       }}/>
            </label>
            {runtimeLimit !== undefined && (
                <small id={`${id}-runtime`}>Лимит подключения: {runtimeLimit.toLocaleString('ru-RU')}</small>
            )}
            <small id={`${id}-hint`}>{hint}</small>
            {equivalent !== null && !error && <small className="models-policy-fx__equivalent">
                Расчётный лимит: <strong>{formatUsd(equivalent)}</strong>
            </small>}
            {error && <small id={`${id}-error`} className="models-policy-form__numeric-error" aria-live="polite">{error}</small>}
            {aboveRuntime && (
                <small id={`${id}-warning`} className="models-policy-form__numeric-warning">
                    Выше лимита подключения. Сервер применит более строгое ограничение.
                </small>
            )}
        </div>
    )
}

export function PolicyFormSettings({control, catalog, effectiveCatalog, runtime, pending, section}: Props) {
    const {formId, draft, limitsControlRef, budgetControlRef, setDraft} = control
    const {ratePending, setRatePending} = control
    return (
        <div className={`models-policy-form__settings-grid models-policy-form__settings-grid--${section}`}
             id={section === 'limits' ? `${formId}-limits` : undefined}
             data-policy-block={section === 'limits' ? 'limits' : undefined}
             tabIndex={section === 'limits' ? -1 : undefined}>
            {section === 'access' && (
                <>
                    <div
                        id={`${formId}-default`}
                        tabIndex={-1}
                        data-policy-block="access"
                        className={
                            'models-policy-form__setting-field models-policy-form__setting-field--access'
                        }
                    >
                        <span
                            className={
                                'models-label-row'
                            }
                        >
                            Модель по умолчанию

                            <InfoHint
                                text={
                                    'Используется, если запрос не выбрал модель явно. '
                                    + 'Статус рядом показывает связь последней и действующей '
                                    + 'версий каталога с текущим подключением. '
                                    + 'Автоматический режим следует текущему подключению; '
                                    + 'закреплённый ключ останется выбранным после смены подключения '
                                    + 'и может перестать быть исполняемым.'
                                }
                            />
                        </span>

                        <DefaultModelSelector
                            catalog={
                                catalog
                            }
                            effectiveCatalog={
                                effectiveCatalog
                            }
                            runtime={
                                runtime
                            }
                            allowModelKeys={
                                draft.allowModelKeys
                            }
                            denyModelKeys={
                                draft.denyModelKeys
                            }
                            value={
                                draft.defaultModelKey
                            }
                            disabled={
                                pending
                            }
                            onChange={(
                                value,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        defaultModelKey:
                                            value,
                                    }),
                                )
                            }}
                        />
                        <p className="models-policy-form__field-explainer">
                            Автоматически — использовать текущую модель сервера при каждом запросе.
                            Закрепить модель — сохранить ключ каталога; при смене подключения
                            совместимость будет проверяться заново, а запрос может быть отклонён.
                        </p>
                    </div>
                </>
            )}
            {section === 'limits' && (
                <>
                    <PolicyNumericInput control={control} field="maxInputTokens" idSuffix="input"
                        section="limits" label="Максимум входных токенов" pending={pending}
                        placeholder="Без дополнительного лимита" runtimeLimit={runtime.maxInputTokens}
                        inputRef={limitsControlRef}
                        hint="Целое число от 1 до 2 147 483 647, например 32000. Пустое поле — без дополнительного лимита."/>
                    <PolicyNumericInput control={control} field="maxOutputTokens" idSuffix="output"
                        section="limits" label="Максимум выходных токенов" pending={pending}
                        placeholder="Без дополнительного лимита" runtimeLimit={runtime.maxOutputTokens}
                        hint="Целое число от 1 до 2 147 483 647, например 1024. Пустое поле — без дополнительного лимита."/>
                    <p className="models-policy-form__field-explainer models-policy-form__full-row">
                        Сервер применяет наиболее строгое из действующих ограничений подключения, каталога и организации.
                    </p>
                </>
            )}
            {section === 'budget' && (
                <>
                    <PolicyBudgetCurrency control={control} pending={pending} onLoadingChange={setRatePending}/>
                    <PolicyNumericInput control={control} field="maxRequestCostUsd" idSuffix="request-cost"
                        section="budget" label={`Лимит на один запрос, ${draft.rubQuote ? 'RUB' : 'USD'}`} pending={pending || ratePending}
                        placeholder="Без лимита"
                        hint="Максимальная расчётная стоимость запроса, например 0,05. Пустое поле — без лимита."/>
                    <PolicyNumericInput control={control} field="monthlyBudgetUsd" idSuffix="monthly-budget"
                        section="budget" label={`Месячный бюджет, ${draft.rubQuote ? 'RUB' : 'USD'}`} pending={pending || ratePending}
                        placeholder="Не задан"
                        hint="Бюджет организации на календарный месяц (UTC), например 100,00. Пустое поле — не задан."/>
                    <label id={`${formId}-budget`} data-policy-block="budget" className="models-policy-form__setting-field models-policy-form__setting-field--budget">
                        <span className="models-label-row">
                            Контроль бюджета
                            <InfoHint text="Мягкий режим фиксирует превышение, жёсткий блокирует запрос до обращения к провайдеру."/>
                        </span>
                        <select ref={budgetControlRef} value={draft.budgetEnforcement} disabled={pending}
                                onChange={event => setDraft(current => ({...current, budgetEnforcement: event.target.value as BudgetEnforcement}))}>
                            {BUDGET_ENFORCEMENTS.map(value => <option key={value} value={value}>{budgetEnforcementLabel(value)}</option>)}
                        </select>
                    </label>
                    <p className="models-policy-form__field-explainer models-policy-form__full-row">
                        {draft.rubQuote ? 'Суммы в рублях (RUB), конвертация в USD с округлением вниз до 12 знаков.' : 'Суммы в долларах США (USD).'} Допустимы точка или запятая, до 18 цифр до разделителя
                        и до 12 после него, без разделителей тысяч. Ноль — установленный нулевой лимит.
                    </p>
                        {!draft.monthlyBudgetUsd.trim() && (
                            <p className="models-policy-form__field-explainer models-policy-form__full-row">
                                Бюджет не установлен: месячного порога нет.
                                Контроль месячного бюджета начнёт действовать после установки суммы.
                            </p>
                        )}
                        {draft.monthlyBudgetUsd.trim() && draft.budgetEnforcement === 'HARD' && (
                            <p className="models-policy-form__field-explainer models-policy-form__full-row">
                                Жёсткий контроль проверяется сервером до исполнения запроса;
                                запросы с непроверяемой стоимостью могут отклоняться.
                                Итог проверьте через «Проверить правила».
                            </p>
                        )}
                </>
            )}
        </div>
    )
}

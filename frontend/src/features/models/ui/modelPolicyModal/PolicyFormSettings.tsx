// frontend/src/features/models/ui/modelPolicyModal/PolicyFormSettings.tsx
// Секция формы настройки правил без изменения HTML и CSS-классов.

import type {ModelPolicyModalController} from './useModelPolicyModalController'
import type {ModelPolicyModalProps} from './policyModalTypes'
import {DefaultModelSelector} from '../ModelPolicyModelSelectors'
import {DecimalInput} from '../ModelFormControls'
import {BUDGET_ENFORCEMENTS} from '../../../../entities/model/api/modelApi'
import type {BudgetEnforcement} from '../../../../entities/model/api/modelApi'
import {InfoHint, budgetEnforcementLabel} from './policyModalPresentation'

type Props = {
    control: ModelPolicyModalController
    catalog: ModelPolicyModalProps['catalog']
    effectiveCatalog: ModelPolicyModalProps['effectiveCatalog']
    runtime: ModelPolicyModalProps['runtime']
    pending: ModelPolicyModalProps['pending']
}

export function PolicyFormSettings({control, catalog, effectiveCatalog, runtime, pending}: Props) {
    const {formId, draft, limitsControlRef, budgetControlRef, setDraft} = control
    return (
        <>
                <div
                    className={
                        'models-policy-form__settings-grid'
                    }
                >
                    <div
                        id={`${formId}-default`}
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
                                    + 'версий каталога с текущим Runtime. '
                                    + 'Автоматический режим следует текущему Runtime; '
                                    + 'закрепленный ключ останется выбранным после смены Runtime '
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
                            Закрепить модель — сохранить ключ каталога; при смене Runtime
                            совместимость будет проверяться заново, а запрос может быть отклонен.
                        </p>
                    </div>

                    <label id={`${formId}-budget`} data-policy-block="budget" className="models-policy-form__setting-field models-policy-form__setting-field--budget">
                        <span
                            className={
                                'models-label-row'
                            }
                        >
                            Контроль бюджета

                            <InfoHint
                                text={
                                    'Мягкий режим фиксирует превышение, '
                                    + 'жёсткий блокирует запрос до обращения к провайдеру.'
                                }
                            />
                        </span>

                        <select
                            ref={
                                budgetControlRef
                            }
                            value={
                                draft.budgetEnforcement
                            }
                            onChange={(
                                event,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        budgetEnforcement:
                                            event.target.value as BudgetEnforcement,
                                    }),
                                )
                            }}
                        >
                            {BUDGET_ENFORCEMENTS.map(
                                (
                                    value,
                                ) => (
                                    <option
                                        key={
                                            value
                                        }
                                        value={
                                            value
                                        }
                                    >
                                        {budgetEnforcementLabel(
                                            value,
                                        )}
                                    </option>
                                ),
                            )}
                        </select>
                    </label>

                    <label id={`${formId}-input`} data-policy-block="limits" className="models-policy-form__setting-field models-policy-form__setting-field--limits">
                        Входные токены, максимум

                        <input
                            ref={
                                limitsControlRef
                            }
                            inputMode="numeric"
                            placeholder="Например: 32000"
                            value={
                                draft.maxInputTokens
                            }
                            onChange={(
                                event,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        maxInputTokens:
                                            event
                                                .target
                                                .value,
                                    }),
                                )
                            }}
                        />
                    </label>

                    <label id={`${formId}-output`} data-policy-block="limits" className="models-policy-form__setting-field models-policy-form__setting-field--limits">
                        Выходные токены, максимум

                        <input
                            inputMode="numeric"
                            placeholder="Например: 4096"
                            value={
                                draft.maxOutputTokens
                            }
                            onChange={(
                                event,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        maxOutputTokens:
                                            event
                                                .target
                                                .value,
                                    }),
                                )
                            }}
                        />
                    </label>

                    <div id={`${formId}-request-cost`} data-policy-block="budget" className="models-policy-form__setting-field models-policy-form__setting-field--budget">
                        <DecimalInput
                            label="Стоимость запроса, USD"
                            placeholder="Без лимита"
                            value={
                                draft.maxRequestCostUsd
                            }
                            onChange={(
                                value,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        maxRequestCostUsd:
                                            value,
                                    }),
                                )
                            }}
                        />
                    </div>

                    <div id={`${formId}-monthly-budget`} data-policy-block="budget" className="models-policy-form__setting-field models-policy-form__setting-field--budget">
                        <DecimalInput
                            label="Бюджет на месяц, USD"
                            placeholder="Не задан"
                            value={
                                draft.monthlyBudgetUsd
                            }
                            onChange={(
                                value,
                            ) => {
                                setDraft(
                                    (
                                        current,
                                    ) => ({
                                        ...current,
                                        monthlyBudgetUsd:
                                            value,
                                    }),
                                )
                            }}
                        />
                        {!draft.monthlyBudgetUsd.trim() && (
                            <p className="models-policy-form__field-explainer">
                                Бюджет не установлен: месячного порога нет.
                                Режим «{budgetEnforcementLabel(draft.budgetEnforcement)}»
                                начнет влиять на лимит после задания суммы.
                            </p>
                        )}
                        {draft.monthlyBudgetUsd.trim() && draft.budgetEnforcement === 'HARD' && (
                            <p className="models-policy-form__field-explainer">
                                Жесткий контроль проверяется сервером до исполнения запроса;
                                запросы с непроверяемой стоимостью могут отклоняться.
                                Итог проверьте через «Проверить правила».
                            </p>
                        )}
                    </div>
                </div>

        </>
    )
}

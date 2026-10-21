// frontend/src/features/models/ui/modelPolicyModal/PolicyFormOverview.tsx
// Секция формы настройки правил без изменения HTML и CSS-классов.

import type {ModelPolicyModalController} from './useModelPolicyModalController'

type Props = {
    control: ModelPolicyModalController
}

export function PolicyFormOverview({control}: Props) {
    const {formId, activeSection, focusShortcut, accessControlRef, limitsControlRef, budgetControlRef, dataControlRef, draft, setDraft, rulesStatusLabel, rulesStatusHint} = control
    return (
        <>
                <div className="models-policy-form__overview-grid">
                    <div
                        className={
                            'models-policy-form__scope'
                        }
                    >
                    <div
                        className={
                            'models-policy-form__scope-copy'
                        }
                    >
                        <strong>
                            Что настраивает это окно
                        </strong>

                        <p
                            className={
                                'models-form__hint'
                            }
                        >
                            Здесь меняется только блок
                            {' '}
                            «Доступ и ограничения».
                            Фактический провайдер и модель
                            {' '}
                            задаются конфигурацией Runtime на сервере.
                            {' '}
                            Правила организации их не переключают.
                        </p>
                    </div>

                    <nav
                        className={
                            'models-policy-form__scope-tags'
                        }
                        aria-label={
                            'Быстрый переход по настройкам'
                        }
                    >
                        <button
                            type="button"
                            className={
                                'models-policy-form__scope-tag models-policy-form__scope-tag--access'
                            }
                            aria-pressed={activeSection === 'access'}
                            aria-controls={`${formId}-access ${formId}-default`}
                            onClick={() => {
                                focusShortcut(
                                    'access',
                                    accessControlRef.current,
                                )
                            }}
                        >
                            Доступ
                        </button>

                        <button
                            type="button"
                            className={
                                'models-policy-form__scope-tag models-policy-form__scope-tag--limits'
                            }
                            aria-pressed={activeSection === 'limits'}
                            aria-controls={`${formId}-input ${formId}-output`}
                            onClick={() => {
                                focusShortcut(
                                    'limits',
                                    limitsControlRef.current,
                                )
                            }}
                        >
                            Лимиты
                        </button>

                        <button
                            type="button"
                            className={
                                'models-policy-form__scope-tag models-policy-form__scope-tag--budget'
                            }
                            aria-pressed={activeSection === 'budget'}
                            aria-controls={`${formId}-budget ${formId}-request-cost ${formId}-monthly-budget`}
                            onClick={() => {
                                focusShortcut(
                                    'budget',
                                    budgetControlRef.current,
                                )
                            }}
                        >
                            Бюджет
                        </button>

                        <button
                            type="button"
                            className={
                                'models-policy-form__scope-tag models-policy-form__scope-tag--data'
                            }
                            aria-pressed={activeSection === 'data'}
                            aria-controls={`${formId}-data`}
                            onClick={() => {
                                focusShortcut(
                                    'data',
                                    dataControlRef.current,
                                )
                            }}
                        >
                            Требования к данным
                        </button>
                    </nav>
                    </div>

                    <div
                        className={
                            'models-policy-form__toggle '
                            + (draft.enabled
                                ? 'models-policy-form__toggle--enabled'
                                : 'models-policy-form__toggle--disabled')
                        }
                    >
                    <label
                        className={
                            'models-policy-form__switch-card'
                        }
                    >
                        <span
                            className={
                                'models-policy-form__switch'
                            }
                        >
                            <input
                                type="checkbox"
                                checked={
                                    draft.enabled
                                }
                                aria-label={
                                    rulesStatusLabel
                                }
                                onChange={(
                                    event,
                                ) => {
                                    setDraft(
                                        (
                                            current,
                                        ) => ({
                                            ...current,
                                            enabled:
                                                event
                                                    .target
                                                    .checked,
                                        }),
                                    )
                                }}
                            />

                            <span
                                className={
                                    'models-policy-form__switch-track'
                                }
                            >
                                <span
                                    className={
                                        'models-policy-form__switch-thumb'
                                    }
                                />
                            </span>
                        </span>

                        <span
                            className={
                                'models-policy-form__switch-copy'
                            }
                        >
                            <strong>
                                {rulesStatusLabel}
                            </strong>

                            <small>
                                {rulesStatusHint}
                            </small>
                        </span>
                    </label>

                    <p
                        className={
                            'models-policy-form__toggle-note'
                        }
                    >
                        {draft.enabled
                            ? (
                                'Сейчас ограничения этой '
                                + 'организации включены.'
                            )
                            : (
                                'Сейчас ограничения этой '
                                + 'организации отключены.'
                            )}
                    </p>
                    </div>
                </div>

        </>
    )
}

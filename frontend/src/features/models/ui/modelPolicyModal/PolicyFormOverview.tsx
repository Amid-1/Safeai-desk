// frontend/src/features/models/ui/modelPolicyModal/PolicyFormOverview.tsx
// Постоянный статус правил. Переключение разделов реализовано доступными вкладками.

import type {ModelPolicyModalController} from './useModelPolicyModalController'

export function PolicyFormOverview({control}: {control: ModelPolicyModalController}) {
    const {draft, setDraft, rulesStatusLabel, rulesStatusHint, pending} = control
    return (
        <div className={`models-policy-form__toggle models-policy-form__toggle--${draft.enabled ? 'enabled' : 'disabled'} models-policy-form__status-bar`}>
            <label className="models-policy-form__switch-card">
                <span className="models-policy-form__switch">
                    <input
                        type="checkbox"
                        checked={draft.enabled}
                        disabled={pending}
                        aria-label={rulesStatusLabel}
                        onChange={(event) => setDraft((current) => ({
                            ...current,
                            enabled: event.target.checked,
                        }))}
                    />
                    <span className="models-policy-form__switch-track">
                        <span className="models-policy-form__switch-thumb" />
                    </span>
                </span>
                <span className="models-policy-form__switch-copy">
                    <strong>{rulesStatusLabel}</strong>
                    <small>{rulesStatusHint}</small>
                </span>
            </label>
            <details className="models-policy-form__status-help">
                <summary>О настройках</summary>
                <p>
                    Подключённый AI-провайдер задаётся на сервере.
                    Здесь настраиваются только правила организации.
                </p>
            </details>
        </div>
    )
}

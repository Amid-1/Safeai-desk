// frontend/src/features/models/ui/modelPolicyModal/PolicyFormPreview.tsx
// Секция формы настройки правил без изменения HTML и CSS-классов.

import type {ModelPolicyModalController} from './useModelPolicyModalController'
import {ErrorState} from '../../../../shared/ui/StateBlock'
import {previewReasonLabel} from './policyModalPresentation'

type Props = {
    control: ModelPolicyModalController
}

export function PolicyFormPreview({control}: Props) {
    const {activationWarning, previewError, policyPreview, currentPreview, lockoutAcknowledged, setLockoutAcknowledged} = control
    return (
        <>
                {activationWarning
                    && (
                        <div
                            className={
                                'models-policy-form__warning'
                            }
                            role="alert"
                        >
                            <strong>
                                Сейчас нет действующей
                                {' '}
                                записи каталога,
                                {' '}
                                совпадающей с подключённой моделью.
                            </strong>

                            <p>
                                Если сохранить правила включёнными,
                                {' '}
                                запросы этой организации будут
                                {' '}
                                предсказуемо отклоняться до обращения
                                {' '}
                                к провайдеру. Перед сохранением включённых
                                {' '}
                                правил выполните серверную проверку черновика.
                            </p>
                        </div>
                    )}

                {previewError && (
                    <ErrorState
                        message={previewError}
                        variant="inline"
                    />
                )}

                {policyPreview && !currentPreview && (
                    <p className="models-policy-form__preview-stale" role="status">
                        Правила изменены после предыдущей проверки.
                        Запустите «Проверить правила» заново перед сохранением.
                    </p>
                )}

                {currentPreview && (
                    <section
                        className={
                            'models-policy-form__preview '
                            + (currentPreview.wouldLockOutOrganization
                                ? 'models-policy-form__preview--danger'
                                : 'models-policy-form__preview--ok')
                        }
                        aria-live="polite"
                    >
                        <div className="models-policy-form__preview-summary">
                            <div>
                                <strong>Серверная проверка черновика</strong>
                                <small>
                                    Подключение: {currentPreview.runtimeProvider}/{currentPreview.runtimeModel}
                                    {' · '}
                                    Исполняемых моделей: {currentPreview.executableModelCount}
                                    {' · '}
                                    Проверено сервером: {new Date(currentPreview.evaluatedAt).toLocaleString('ru-RU')}
                                </small>
                            </div>

                            <span>
                                {currentPreview.automaticOutcome === 'ALLOWED'
                                    ? 'Автовыбор разрешён'
                                    : 'Автовыбор отклонён'}
                                {' · '}
                                {previewReasonLabel(currentPreview.automaticReason)}
                            </span>
                        </div>

                        <div className="models-policy-form__preview-list">
                            {currentPreview.models.map((item) => (
                                <div
                                    key={`${item.modelKey}:${item.catalogVersion}`}
                                    className="models-policy-form__preview-row"
                                >
                                    <code>{item.modelKey}</code>
                                    <span>
                                        v{item.catalogVersion}
                                        {' · '}
                                        {item.outcome === 'ALLOWED' ? 'Разрешено' : 'Отклонено'}
                                    </span>
                                    <small>
                                        {item.reason
                                            ? previewReasonLabel(item.reason)
                                            : 'Проходит все проверки'}
                                        {' · '}
                                        Лимиты: {item.effectiveInputLimit.toLocaleString('ru-RU')}
                                        {' / '}{item.effectiveOutputLimit.toLocaleString('ru-RU')}
                                        {' · '}
                                        {item.pricingComplete
                                            ? 'Тариф подтвержден'
                                            : 'Данные о стоимости неполные'}
                                    </small>
                                </div>
                            ))}
                        </div>

                        {currentPreview.wouldLockOutOrganization && (
                            <label className="models-policy-form__lockout-confirm">
                                <input
                                    type="checkbox"
                                    checked={lockoutAcknowledged}
                                    onChange={(event) => {
                                        setLockoutAcknowledged(event.target.checked)
                                    }}
                                />
                                <span>
                                    Подтверждаю осознанную блокировку всех исполняемых моделей этой организации.
                                </span>
                            </label>
                        )}
                    </section>
                )}

        </>
    )
}

// frontend/src/features/models/ui/modelPolicyModal/PolicyFormPreview.tsx
// Серверная оценка черновика: состояния, ограничения и осознанная блокировка.

import type {ModelPolicyModalController} from './useModelPolicyModalController'
import {ErrorState} from '../../../../shared/ui/StateBlock'
import { previewReasonLabel } from './policyModalLabels'
import {POLICY_PREVIEW_LABEL, policyPreviewState} from './policyPreviewStatus'

type Props = {control: ModelPolicyModalController}

const CHECKS = [
    ['Доступ к моделям', 'Разрешения и запреты'],
    ['Совместимость', 'Подключение и каталог'],
    ['Стоимость и лимиты', 'Бюджет, токены и тарифы'],
    ['Требования к данным', 'Обучение и хранение'],
] as const

export function PolicyFormPreview({control}: Props) {
    const {activationWarning, previewError, policyPreview, currentPreview,
        lockoutAcknowledged, setLockoutAcknowledged, previewPending, pending,
        policyChangedExternally, handlePreview} = control

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

            <section className="models-policy-check__criteria" aria-label="Параметры проверки">
                <h4>Что будет проверено</h4>
                <div className="models-policy-check__criteria-grid">
                    {CHECKS.map(([title, description]) => (
                        <div key={title} className="models-policy-check__criterion">
                            <strong>{title}</strong>
                            <small>{description}</small>
                        </div>
                    ))}
                </div>
            </section>

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
                    <p>Запустите предварительную проверку, чтобы увидеть доступные модели, ограничения и решение сервера.</p>
                    <button type="button" className="btn-primary"
                            disabled={pending || policyChangedExternally}
                            onClick={() => {void handlePreview()}}>
                        Запустить проверку
                    </button>
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
                            <strong>Серверная проверка черновика</strong>
                            <small>
                                Подключение: {currentPreview.runtimeProvider}/{currentPreview.runtimeModel}
                                {' · '}Исполняемых моделей: {currentPreview.executableModelCount}
                                {' · '}Проверено сервером: {new Date(currentPreview.evaluatedAt).toLocaleString('ru-RU')}
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
                                        {item.outcome === 'ALLOWED' ? 'Доступ разрешён' : 'Доступ отклонён'}
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
                                </div>
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

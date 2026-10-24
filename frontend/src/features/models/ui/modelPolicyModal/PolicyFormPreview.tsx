// frontend/src/features/models/ui/modelPolicyModal/PolicyFormPreview.tsx
// Серверная оценка черновика: состояния, ограничения и осознанная блокировка.

import type {ModelPolicyModalController} from './useModelPolicyModalController'
import {ErrorState} from '../../../../shared/ui/StateBlock'
import {previewReasonLabel} from './policyModalPresentation'

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

    const statusLabel = previewPending ? 'Проверяется'
        : currentPreview ? 'Проверено'
            : policyPreview ? 'Требуется повторная проверка'
                : previewError ? 'Ошибка проверки' : 'Не проверено'

    return (
        <div className="models-policy-check">
            <div className="models-policy-check__intro">
                <div>
                    <strong>Проверка правил доступа</strong>
                    <p>Сервер оценит текущие настройки и покажет, какие модели доступны и почему отдельные варианты могут быть отклонены.
                        Проверка не отправляет пользовательский запрос нейросети.</p>
                </div>
                <span className="models-policy-check__status" role="status">{statusLabel}</span>
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
                    (currentPreview.wouldLockOutOrganization
                        ? 'models-policy-form__preview--danger' : 'models-policy-form__preview--ok')}
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

                    <div className="models-policy-form__preview-list">
                        {currentPreview.models.map(item => (
                            <div key={`${item.modelKey}:${item.catalogVersion}`} className="models-policy-form__preview-row">
                                <code>{item.modelKey}</code>
                                <span>v{item.catalogVersion}{' · '}{item.outcome === 'ALLOWED' ? 'Разрешено' : 'Отклонено'}</span>
                                <small>
                                    {item.reason ? previewReasonLabel(item.reason) : 'Проходит все проверки'}
                                    {' · '}Лимиты: {item.effectiveInputLimit.toLocaleString('ru-RU')}
                                    {' / '}{item.effectiveOutputLimit.toLocaleString('ru-RU')}
                                    {' · '}{item.pricingComplete ? 'Тариф подтверждён' : 'Данные о стоимости неполные'}
                                </small>
                            </div>
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

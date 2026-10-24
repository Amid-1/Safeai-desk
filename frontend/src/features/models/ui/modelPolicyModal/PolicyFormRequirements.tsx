// frontend/src/features/models/ui/modelPolicyModal/PolicyFormRequirements.tsx
// Требования к данным и пояснение статуса NOT_DECLARED без обещания серверного допуска.

import type {ModelPolicyModalController} from './useModelPolicyModalController'

type Props = {control: ModelPolicyModalController; pending?: boolean}

export function PolicyFormRequirements({control, pending = false}: Props) {
    const {formId, draft, setDraft, dataControlRef, undeclaredTrainingModels,
        serverConfirmedTrainingLockout, serverTrainingRejectedModelKeys, currentPreview} = control

    return (
        <fieldset id={`${formId}-data`} data-policy-block="data"
                  className="models-policy-form__requirements">
            <legend>Дополнительные требования</legend>
            <p id={`${formId}-training-hint`} className="models-form__hint">
                Если включено требование «Не использовать данные для обучения», в каталоге должно
                быть подтверждено, что провайдер не использует данные для обучения либо это
                запрещено договором. Отсутствие сведений не считается подтверждением.
            </p>

            <div aria-live="polite" aria-atomic="true">
                {draft.requireNoTraining && undeclaredTrainingModels.length > 0 && (
                    <div className={'models-policy-form__warning' +
                        (serverConfirmedTrainingLockout ? ' models-policy-form__warning--danger' : '')}
                         role={serverConfirmedTrainingLockout ? 'alert' : 'status'}>
                        <strong>{serverConfirmedTrainingLockout
                            ? 'Сервер подтвердил блокировку модели'
                            : draft.enabled ? 'Запрет обучения не подтверждён'
                                : 'При включении правил запрет обучения не будет подтверждён'}</strong>
                        {serverConfirmedTrainingLockout ? (
                            <p>Серверная проверка подтвердила блокировку: модели
                                {' '}{serverTrainingRejectedModelKeys.join(', ')}
                                {' '}не соответствуют требованию о запрете обучения.
                                {' '}Доступных для выполнения моделей: {currentPreview?.executableModelCount ?? 0}.
                                {' '}Если сохранить эти правила включёнными, организация
                                не сможет использовать модели при текущей конфигурации.
                            </p>
                        ) : (
                            <>
                                <p>Для моделей
                                    {' '}{undeclaredTrainingModels.map(entry => entry.modelKey).join(', ')}
                                    {' '}отсутствуют подтверждённые сведения об использовании данных для обучения.
                                    Это не подтверждает выполнение выбранного требования.</p>
                                <p>Это предварительное предупреждение. Чтобы получить решение сервера,
                                    откройте вкладку «Проверка».</p>
                            </>
                        )}
                        <details className="models-policy-form__warning-help">
                            <summary>Как устранить несовместимость?</summary>
                            <p>Если провайдер предоставил подтверждённые сведения, создайте новую
                                версию записи модели в каталоге. Не меняйте условия обработки данных
                                без документального подтверждения.</p>
                        </details>
                    </div>
                )}
            </div>

            <div className="models-form__checks">
                <label>
                    <input ref={dataControlRef} type="checkbox" disabled={pending}
                           checked={draft.requireCompletePricing}
                           onChange={event => setDraft(current => ({...current, requireCompletePricing: event.target.checked}))}/>
                    Полные данные о стоимости
                </label>
                <label>
                    <input type="checkbox" disabled={pending}
                           checked={draft.requireNoTraining}
                           aria-describedby={`${formId}-training-hint`}
                           onChange={event => setDraft(current => ({...current, requireNoTraining: event.target.checked}))}/>
                    Не использовать данные для обучения
                </label>
                <label>
                    <input type="checkbox" disabled={pending}
                           checked={draft.requireZeroDataRetention}
                           onChange={event => setDraft(current => ({...current, requireZeroDataRetention: event.target.checked}))}/>
                    Не хранить данные после запроса
                </label>
            </div>
        </fieldset>
    )
}

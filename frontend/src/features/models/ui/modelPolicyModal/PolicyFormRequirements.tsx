// frontend/src/features/models/ui/modelPolicyModal/PolicyFormRequirements.tsx
// Секция формы настройки правил без изменения HTML и CSS-классов.

import type {ModelPolicyModalController} from './useModelPolicyModalController'

type Props = {
    control: ModelPolicyModalController
    pending?: boolean
}

export function PolicyFormRequirements({control, pending = false}: Props) {
    const {formId, draft, setDraft, dataControlRef, undeclaredTrainingModels, serverConfirmedTrainingLockout, serverTrainingRejectedModelKeys, currentPreview} = control
    return (
        <>
                <fieldset
                    id={`${formId}-data`}
                    data-policy-block="data"
                    className={
                        'models-policy-form__requirements'
                    }
                >
                    <legend>
                        Дополнительные требования
                    </legend>

                    <p id={`${formId}-training-hint`} className="models-form__hint">
                        Запрет обучения требует подтверждения в действующей версии каталога:
                        «Не используются» (NOT_USED) или договорного запрета
                        (CONTRACTUAL_NO_TRAINING). «Не заявлено» (NOT_DECLARED)
                        не подтверждает запрет, даже для бесплатной модели.
                    </p>
                    <div aria-live="polite" aria-atomic="true">
                        {draft.requireNoTraining && undeclaredTrainingModels.length > 0 && (
                            <div
                                className={
                                    'models-policy-form__warning'
                                    + (serverConfirmedTrainingLockout
                                        ? ' models-policy-form__warning--danger'
                                        : '')
                                }
                                role={
                                    serverConfirmedTrainingLockout
                                        ? 'alert'
                                        : 'status'
                                }
                            >
                                <strong>
                                    {serverConfirmedTrainingLockout
                                        ? 'Правила блокируют доступные модели'
                                        : draft.enabled
                                            ? 'Несовместимость с требованием запрета обучения'
                                            : 'При включении правил возникнет несовместимость'}
                                </strong>

                                {serverConfirmedTrainingLockout
                                    ? (
                                        <p>
                                            Серверная проверка подтвердила блокировку:
                                            {' '}требованию «Не использовать данные для обучения»
                                            {' '}не соответствуют модели:
                                            {' '}{serverTrainingRejectedModelKeys.join(', ')}.
                                            {' '}Исполняемых моделей:
                                            {' '}{currentPreview?.executableModelCount ?? 0}.
                                        </p>
                                    )
                                    : (
                                        <p>
                                            В действующем каталоге использование данных для обучения
                                            не заявлено (NOT_DECLARED):
                                            {' '}{undeclaredTrainingModels.map((entry) => entry.modelKey).join(', ')}.
                                            {' '}Эти модели не соответствуют выбранному требованию.
                                            {' '}Проверьте черновик на сервере, чтобы увидеть итоговую
                                            доступность с учётом всех правил и текущей модели сервера.
                                        </p>
                                    )}
                            </div>
                        )}
                    </div>

                    <div
                        className={
                            'models-form__checks'
                        }
                    >
                        <label>
                            <input
                                ref={
                                    dataControlRef
                                }
                                type="checkbox"
                                disabled={pending}
                                checked={
                                    draft.requireCompletePricing
                                }
                                onChange={(
                                    event,
                                ) => {
                                    setDraft(
                                        (
                                            current,
                                        ) => ({
                                            ...current,
                                            requireCompletePricing:
                                                event
                                                    .target
                                                    .checked,
                                        }),
                                    )
                                }}
                            />

                            Полные данные о стоимости
                        </label>

                        <label>
                            <input
                                type="checkbox"
                                disabled={pending}
                                checked={
                                    draft.requireNoTraining
                                }
                                aria-describedby={`${formId}-training-hint`}
                                onChange={(
                                    event,
                                ) => {
                                    setDraft(
                                        (
                                            current,
                                        ) => ({
                                            ...current,
                                            requireNoTraining:
                                                event
                                                    .target
                                                    .checked,
                                        }),
                                    )
                                }}
                            />

                            Не использовать данные
                            {' '}
                            для обучения
                        </label>

                        <label>
                            <input
                                type="checkbox"
                                disabled={pending}
                                checked={
                                    draft.requireZeroDataRetention
                                }
                                onChange={(
                                    event,
                                ) => {
                                    setDraft(
                                        (
                                            current,
                                        ) => ({
                                            ...current,
                                            requireZeroDataRetention:
                                                event
                                                    .target
                                                    .checked,
                                        }),
                                    )
                                }}
                            />

                            Не хранить данные
                            {' '}
                            после запроса
                        </label>
                    </div>
                </fieldset>

        </>
    )
}

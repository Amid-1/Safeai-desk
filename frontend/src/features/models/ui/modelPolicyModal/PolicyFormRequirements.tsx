// frontend/src/features/models/ui/modelPolicyModal/PolicyFormRequirements.tsx
// Требования к данным и пояснение статуса NOT_DECLARED без обещания серверного допуска.

import type {ModelPolicyModalController} from './useModelPolicyModalController'
import type {ModelPolicyModalProps} from './policyModalTypes'

type Props = {control: ModelPolicyModalController; pending?: boolean;
    effectiveCatalog: ModelPolicyModalProps['effectiveCatalog']; runtime: ModelPolicyModalProps['runtime']}

export function PolicyFormRequirements({control, pending = false, effectiveCatalog, runtime}: Props) {
    const {formId, draft, setDraft, dataControlRef, undeclaredTrainingModels,
        serverConfirmedTrainingLockout, serverTrainingRejectedModelKeys, currentPreview} = control
    const candidates = effectiveCatalog.filter(entry => entry.provider === runtime.provider
        && entry.providerModelId === runtime.model && (entry.lifecycle === 'ACTIVE' || entry.lifecycle === 'DEPRECATED'))
    const trainingRejected = currentPreview?.models.filter(item => item.outcome === 'DENIED'
        && item.reason === 'TRAINING_POLICY_UNSATISFIED').map(item => item.modelKey) ?? []
    const issues = [
        {enabled: draft.requireCompletePricing, reason: 'PRICING_INCOMPLETE', title: 'Сведения о стоимости неполные',
            keys: candidates.filter(entry => !entry.pricingComplete || (entry.pricingStatus !== 'FREE' && entry.pricingStatus !== 'CONFIGURED')).map(entry => entry.modelKey)},
        {enabled: draft.requireZeroDataRetention, reason: 'RETENTION_POLICY_UNSATISFIED', title: 'Отсутствие хранения данных не подтверждено',
            keys: candidates.filter(entry => entry.retentionStatus !== 'ZERO_DATA_RETENTION'
                || runtime.dataAssurance?.zeroDataRetention !== true).map(entry => entry.modelKey)},
        {enabled: draft.requireNoTraining && undeclaredTrainingModels.length === 0, reason: 'TRAINING_POLICY_UNSATISFIED', title: 'Требование о запрете обучения не выполнено',
            keys: candidates.filter(entry => entry.trainingUseStatus === 'MAY_BE_USED'
                || runtime.dataAssurance?.noTraining !== true).map(entry => entry.modelKey)},
    ]

    return (
        <fieldset id={`${formId}-data`} tabIndex={-1} data-policy-block="data"
                  className="models-policy-form__requirements">
            <legend>Требования к моделям</legend>
            <div className="models-form__checks models-policy-form__requirement-options">
                <div>
                    <label><input ref={dataControlRef} type="checkbox" disabled={pending}
                           checked={draft.requireCompletePricing}
                           aria-describedby={`${formId}-pricing-hint`}
                           onChange={event => setDraft(current => ({...current, requireCompletePricing: event.target.checked}))}/>
                    Полные сведения о стоимости</label>
                    <p id={`${formId}-pricing-hint`}>Разрешать модели с поддерживаемым и полным тарифом. Модель не обязана быть бесплатной.</p>
                </div>
                <div>
                    <label><input type="checkbox" disabled={pending}
                           checked={draft.requireNoTraining}
                           aria-describedby={`${formId}-training-hint`}
                           onChange={event => setDraft(current => ({...current, requireNoTraining: event.target.checked}))}/>
                    Запрет использования данных для обучения</label>
                    <p id={`${formId}-training-hint`}>Требовать подтверждения, что AI-провайдер не использует данные организации для обучения моделей. Необходимы сведения каталога и действующее подтверждение подключения. Отсутствие сведений не считается подтверждением.</p>
                </div>
                <div>
                    <label><input type="checkbox" disabled={pending}
                           checked={draft.requireZeroDataRetention}
                           aria-describedby={`${formId}-retention-hint`}
                           onChange={event => setDraft(current => ({...current, requireZeroDataRetention: event.target.checked}))}/>
                    Нулевое хранение данных у провайдера</label>
                    <p id={`${formId}-retention-hint`}>Требовать подтверждённый режим Zero Data Retention у провайдера. Не влияет на хранение истории чатов в SafeAI Desk.</p>
                </div>
            </div>

            <div aria-live="polite" aria-atomic="true">
                {issues.filter(issue => issue.enabled).map(issue => {
                    const rejected = currentPreview?.models.filter(item => item.outcome === 'DENIED' && item.reason === issue.reason).map(item => item.modelKey) ?? []
                    const keys = rejected.length ? rejected : issue.keys
                    if (!keys.length) return null
                    return <div key={issue.reason} className="models-policy-form__warning" role="status">
                        <strong>{issue.title}</strong>
                        <p>{keys.join(', ')}. {rejected.length
                            ? 'Серверная проверка отклонила указанные модели по этому требованию.'
                            : 'Предварительное предупреждение по сведениям каталога. Решение принимает сервер; проверьте правила.'}</p>
                        {!draft.enabled && <p>Требование будет применяться только при включённых правилах.</p>}
                    </div>
                })}
                {draft.requireNoTraining && undeclaredTrainingModels.length > 0 && (
                    <div className={'models-policy-form__warning' +
                        (serverConfirmedTrainingLockout ? ' models-policy-form__warning--danger' : '')}
                         role={serverConfirmedTrainingLockout ? 'alert' : 'status'}>
                        <strong>{serverConfirmedTrainingLockout
                            ? 'Сервер подтвердил блокировку модели'
                            : trainingRejected.length ? 'Сервер отклонил модели по требованию запрета обучения'
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
                        ) : trainingRejected.length ? (
                            <p>{trainingRejected.join(', ')} не соответствуют требованию о запрете обучения по результату проверки черновика.</p>
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

        </fieldset>
    )
}

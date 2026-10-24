// frontend/src/features/models/ui/ModelPolicyModal.tsx
// Модальное окно правил: вкладки сохраняют общий черновик и защиту серверной проверки.

import Modal from '../../../shared/ui/Modal'
import type {ModalResizeOptions} from '../../../shared/ui/Modal'
import {ErrorState} from '../../../shared/ui/StateBlock'
import type {ModelPolicyModalProps} from './modelPolicyModal/policyModalTypes'
import {useModelPolicyModalController} from './modelPolicyModal/useModelPolicyModalController'
import {PolicyFormOverview} from './modelPolicyModal/PolicyFormOverview'
import {PolicyFormPreview} from './modelPolicyModal/PolicyFormPreview'
import {PolicyFormSelectors} from './modelPolicyModal/PolicyFormSelectors'
import {PolicyFormSettings} from './modelPolicyModal/PolicyFormSettings'
import {PolicyFormRequirements} from './modelPolicyModal/PolicyFormRequirements'
import {PolicyFormTabs} from './modelPolicyModal/PolicyFormTabs'
import {policyPreviewState} from './modelPolicyModal/policyPreviewStatus'
import './ModelControlPlaneModalShell.css'
import './ModelPolicyModal.css'
import './ModelPolicyProductionEnhancements.css'
import '../styles/ModelControlPlaneReadability.css'
import './ModelPolicyTabs.css'
import './ModelPolicySelectorsScale.css'
import './ModelPolicyCheck.css'

const POLICY_MODAL_RESIZE: ModalResizeOptions = {
    initialWidth: 1120,
    initialHeight: 770,
    minWidth: 320,
    minHeight: 400,
    scaleContent: false,
    maximizable: true,
}

export function ModelPolicyModal(props: ModelPolicyModalProps) {
    // При смене tenant не переиспользуем state черновика предыдущей организации.
    // Версия в key намеренно отсутствует: конкурентные изменения должны
    // инвалидировать preview, а не молча выбрасывать редактируемый черновик.
    return <ModelPolicyModalSession key={`${props.organizationId}:${props.policy.organizationId}`} {...props} />
}

function ModelPolicyModalSession(props: ModelPolicyModalProps) {
    const {catalog, effectiveCatalog, runtime, organizationId, organizationName, pending} = props
    const control = useModelPolicyModalController(props)
    const {
        formId, isDirty, previewPending, handlePreview, handleSubmit,
        requestClose, activeSection, setActiveSection,
        formError, versionMessage, draft, currentPreview, policyPreview, previewError,
        policyChangedExternally,
    } = control

    const previewStatus = policyPreviewState({currentPreview, policyPreview, previewPending, previewError})

    const footer = (
        <div className="models-policy-modal__footer">
            <div className="models-policy-modal__footer-status" role="status">
                <span className="models-policy-modal__draft-indicator">
                    {isDirty ? 'Есть несохранённые изменения' : 'Изменений нет'}
                </span>
                {draft.enabled && !currentPreview && (
                    <span className="models-policy-modal__check-indicator">Требуется актуальная проверка</span>
                )}
            </div>
            <button
                type="button"
                disabled={pending || previewPending || policyChangedExternally}
                onClick={() => {
                    void handlePreview()
                }}
            >
                {previewPending ? 'Проверяем...' : 'Проверить правила'}
            </button>
            <button
                type="submit"
                form={formId}
                className="btn-primary"
                disabled={pending || previewPending || policyChangedExternally}
            >
                {pending ? 'Сохраняем...' : 'Сохранить правила'}
            </button>
        </div>
    )

    const selectorProps = {control, catalog, effectiveCatalog, runtime, pending}
    const panels = {
        access: (
            <>
                <PolicyFormSelectors {...selectorProps} />
                <PolicyFormSettings {...selectorProps} section="access"/>
            </>
        ),
        limits: <PolicyFormSettings {...selectorProps} section="limits"/>,
        budget: <PolicyFormSettings {...selectorProps} section="budget"/>,
        data: <PolicyFormRequirements control={control} pending={pending}/>,
        preview: <PolicyFormPreview control={control}/>,
    }

    return (
        <Modal
            title="Правила использования моделей"
            footer={footer}
            onClose={requestClose}
            closeDisabled={pending || previewPending}
            closeOnBackdrop={false}
            closeOnEscape
            size="lg"
            className="models-policy-modal"
            resize={POLICY_MODAL_RESIZE}
            dragEnabled={false}
        >
            <form
                id={formId}
                className="models-form models-policy-form models-policy-form--tabs"
                data-active-section={activeSection}
                onSubmit={(event) => {
                    event.preventDefault()
                    void handleSubmit()
                }}
            >
                <header className="models-policy-form__intro models-policy-form__intro--compact">
                    <div className="models-policy-form__organization">
                        <span className="models-policy-form__meta-label">Организация</span>
                        <strong>{organizationName ?? 'Текущая организация'}</strong>
                        <code className="models-form__code">{organizationId}</code>
                    </div>
                    <p className="models-form__hint">{versionMessage}</p>
                </header>

                <PolicyFormOverview control={control}/>

                {policyChangedExternally && (
                    <div className="models-policy-tabs__concurrent-warning" role="alert">
                        На сервере изменилась версия правил. Проверка и сохранение этого черновика
                        заблокированы. Закройте окно и откройте актуальные правила заново.
                    </div>
                )}

                {formError && (
                    <div className="models-policy-tabs__error" role="status">
                        <ErrorState message={formError} variant="inline"/>
                    </div>
                )}

                <PolicyFormTabs
                    formId={formId}
                    selected={activeSection}
                    onSelect={setActiveSection}
                    panels={panels}
                    previewStatus={previewStatus}
                />
            </form>
        </Modal>
    )
}

// frontend/src/features/models/ui/ModelPolicyModal.tsx
// Публичное модальное окно правил: композиция секций и неизменный контракт.

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
import './ModelControlPlaneModalShell.css'
import './ModelPolicyModal.css'
import './ModelPolicyProductionEnhancements.css'
import '../styles/ModelControlPlaneReadability.css'

const POLICY_MODAL_RESIZE:
    ModalResizeOptions = {
        initialWidth: 1480,
        initialHeight: 900,

        minWidth: 420,
        minHeight: 440,

        // Не уменьшаем текст: узкая форма перестраивается и прокручивается.
        scaleContent: false,
        maximizable: true,
    }

export function ModelPolicyModal(props: ModelPolicyModalProps) {
    const {
        catalog, effectiveCatalog, runtime, organizationId,
        organizationName, pending,
    } = props
    const control = useModelPolicyModalController(props)
    const {
        formId, isDirty, previewPending, handlePreview, handleSubmit,
        requestClose, activeSection, formError, versionMessage,
    } = control

    const modalFooter = (
        <div className="models-policy-modal__footer">
            {isDirty && (
                <span className="models-policy-modal__dirty-notice" role="status">
                    Есть несохраненные изменения
                </span>
            )}
            <button
                type="button"
                disabled={
                    pending
                    || previewPending
                }
                onClick={() => {
                    void handlePreview()
                }}
            >
                {previewPending
                    ? 'Проверяем...'
                    : 'Проверить правила'}
            </button>

            <button
                type="submit"
                form={
                    formId
                }
                className="btn-primary"
                disabled={
                    pending
                    || previewPending
                }
            >
                {pending
                    ? 'Сохраняем...'
                    : 'Сохранить правила'}
            </button>
        </div>
    )

    return (
        <Modal
            title="Правила использования моделей"
            footer={
                modalFooter
            }
            onClose={
                requestClose
            }
            closeDisabled={
                pending
                || previewPending
            }
            closeOnBackdrop={
                false
            }
            closeOnEscape={
                true
            }
            size="lg"
            className="models-policy-modal"
            resize={
                POLICY_MODAL_RESIZE
            }
        >
            <form
                data-active-section={activeSection ?? undefined}
                id={
                    formId
                }
                className={
                    'models-form '
                    + 'models-policy-form'
                }
                onSubmit={(
                    event,
                ) => {
                    event.preventDefault()

                    void handleSubmit()
                }}
            >
                <div
                    className={
                        'models-policy-form__intro'
                    }
                >
                    <div
                        className={
                            'models-policy-form__organization'
                        }
                    >
                        <span
                            className={
                                'models-policy-form__meta-label'
                            }
                        >
                            Организация
                        </span>

                        <strong>
                            {organizationName
                                ?? 'Текущая организация'}
                        </strong>

                        <code
                            className={
                                'models-form__code'
                            }
                        >
                            {organizationId}
                        </code>
                    </div>

                    <p
                        className={
                            'models-form__hint'
                        }
                    >
                        {versionMessage}
                    </p>
                </div>

                {formError
                    && (
                        <ErrorState
                            message={
                                formError
                            }
                            variant="inline"
                        />
                    )}

                <PolicyFormOverview control={control} />
                <PolicyFormPreview control={control} />
                <PolicyFormSelectors control={control} catalog={catalog} effectiveCatalog={effectiveCatalog} runtime={runtime} pending={pending} />
                <PolicyFormSettings control={control} catalog={catalog} effectiveCatalog={effectiveCatalog} runtime={runtime} pending={pending} />
                <PolicyFormRequirements control={control} />
            </form>
        </Modal>
    )
}

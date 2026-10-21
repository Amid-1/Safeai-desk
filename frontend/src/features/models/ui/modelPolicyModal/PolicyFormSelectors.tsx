// frontend/src/features/models/ui/modelPolicyModal/PolicyFormSelectors.tsx
// Секция формы настройки правил без изменения HTML и CSS-классов.

import type {ModelPolicyModalController} from './useModelPolicyModalController'
import type {ModelPolicyModalProps} from './policyModalTypes'
import {ModelKeySelector} from '../ModelPolicyModelSelectors'

type Props = {
    control: ModelPolicyModalController
    catalog: ModelPolicyModalProps['catalog']
    effectiveCatalog: ModelPolicyModalProps['effectiveCatalog']
    runtime: ModelPolicyModalProps['runtime']
    pending: ModelPolicyModalProps['pending']
}

export function PolicyFormSelectors({control, catalog, effectiveCatalog, runtime, pending}: Props) {
    const {formId, draft, accessControlRef, setAllowSelectorState, setDenySelectorState, setDraft} = control
    return (
        <>
                <div
                    id={`${formId}-access`}
                    data-policy-block="access"
                    className={
                        'models-policy-form__access-grid'
                    }
                >
                    <ModelKeySelector
                        label="Разрешённые модели"
                        hint={
                            'Если список пуст, разрешены все модели, '
                            + 'кроме явно запрещённых.'
                        }
                        kind="allow"
                        catalog={
                            catalog
                        }
                        effectiveCatalog={
                            effectiveCatalog
                        }
                        runtime={
                            runtime
                        }
                        value={
                            draft.allowModelKeys
                        }
                        conflictingValue={
                            draft.denyModelKeys
                        }
                        disabled={
                            pending
                        }
                        inputRef={
                            accessControlRef
                        }
                        onInteractionStateChange={
                            setAllowSelectorState
                        }
                        onChange={(
                            value,
                        ) => {
                            setDraft(
                                (
                                    current,
                                ) => ({
                                    ...current,
                                    allowModelKeys:
                                        value,
                                }),
                            )
                        }}
                    />

                    <ModelKeySelector
                        label="Запрещённые модели"
                        hint={
                            'Модель не может одновременно '
                            + 'быть в списке разрешённых и запрещённых.'
                        }
                        kind="deny"
                        catalog={
                            catalog
                        }
                        effectiveCatalog={
                            effectiveCatalog
                        }
                        runtime={
                            runtime
                        }
                        value={
                            draft.denyModelKeys
                        }
                        conflictingValue={
                            draft.allowModelKeys
                        }
                        disabled={
                            pending
                        }
                        onInteractionStateChange={
                            setDenySelectorState
                        }
                        onChange={(
                            value,
                        ) => {
                            setDraft(
                                (
                                    current,
                                ) => ({
                                    ...current,
                                    denyModelKeys:
                                        value,
                                }),
                            )
                        }}
                    />
                </div>

        </>
    )
}

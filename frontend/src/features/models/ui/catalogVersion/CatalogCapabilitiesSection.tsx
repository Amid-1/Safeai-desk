// frontend/src/features/models/ui/catalogVersion/CatalogCapabilitiesSection.tsx
// ============================================================
// frontend/src/features/models/ui/catalogVersion/CatalogCapabilitiesSection.tsx
// ============================================================
import { MODEL_CAPABILITIES, MODEL_MODALITIES } from '../../../../entities/model/api/modelApi'
import type { ModelCapability, ModelModality } from '../../../../entities/model/api/modelApi'
import type { CatalogSectionProps } from './catalogSectionTypes'
import { CAPABILITY_LABELS, MODALITY_LABELS, OUTPUT_MODALITIES } from './catalogLabels'
import { SectionHeading } from './catalogPresentation'
import { CheckboxGroup } from '../ModelFormControls'

export function CatalogCapabilitiesSection({draft, setDraft}: CatalogSectionProps) {
    return (
                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--capabilities',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Возможности и типы данных"
                            hint="Заявленные возможности и типы данных. Фактическое исполнение отдельно проверяет Runtime."
                            tone="primary"
                        />

                        <fieldset>
                            <legend>
                                Дополнительные возможности
                            </legend>

                            <CheckboxGroup<ModelCapability>
                                values={MODEL_CAPABILITIES}
                                selected={draft.capabilities}
                                getLabel={(value) =>
                                    CAPABILITY_LABELS[value]}
                                onChange={(capabilities) => {
                                    setDraft((current) => ({
                                        ...current,
                                        capabilities,
                                    }))
                                }}
                            />
                        </fieldset>

                        <div className="models-form__two-columns">
                            <fieldset>
                                <legend>
                                    Входные данные
                                </legend>

                                <CheckboxGroup<ModelModality>
                                    values={MODEL_MODALITIES}
                                    selected={draft.inputModalities}
                                    getLabel={(value) =>
                                        MODALITY_LABELS[value]}
                                    onChange={(inputModalities) => {
                                        setDraft((current) => ({
                                            ...current,
                                            inputModalities,
                                        }))
                                    }}
                                />
                            </fieldset>

                            <fieldset>
                                <legend>
                                    Выходные данные
                                </legend>

                                <CheckboxGroup<ModelModality>
                                    values={OUTPUT_MODALITIES}
                                    selected={draft.outputModalities.filter(
                                        (value) =>
                                            value !== 'IMAGE',
                                    )}
                                    getLabel={(value) =>
                                        MODALITY_LABELS[value]}
                                    onChange={(outputModalities) => {
                                        setDraft((current) => ({
                                            ...current,
                                            outputModalities,
                                        }))
                                    }}
                                />
                            </fieldset>
                        </div>
                    </section>
    )
}

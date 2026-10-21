// frontend/src/features/models/ui/catalogVersion/CatalogDataSection.tsx
// ============================================================
// frontend/src/features/models/ui/catalogVersion/CatalogDataSection.tsx
// ============================================================
import type { ModelRetentionStatus, ModelTrainingUseStatus, ModelPricingStatus } from '../../../../entities/model/api/modelApi'
import { MODEL_RETENTION_STATUSES, MODEL_TRAINING_USE_STATUSES, MODEL_PRICING_STATUSES } from '../../../../entities/model/api/modelApi'
import type { CatalogSectionProps } from './catalogSectionTypes'
import { RETENTION_LABELS, TRAINING_LABELS, PRICING_LABELS } from './catalogLabels'
import { SectionHeading } from './catalogPresentation'
import { normalizeDraftForPricingStatus } from '../modelControlPlaneSupport'

export function CatalogDataSection({draft, setDraft}: CatalogSectionProps) {
    return (
                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--data',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Данные и использование"
                            hint="Хранение данных, использование для обучения и статус стоимости."
                            tone="success"
                        />

                        <div className="models-form__grid">
                            <label>
                                Хранение данных
                                <select
                                    value={draft.retentionStatus}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            retentionStatus:
                                                (
                                                    event.target.value as ModelRetentionStatus
                                                ),
                                        }))
                                    }}
                                >
                                    {MODEL_RETENTION_STATUSES.map(
                                        (value) => (
                                            <option
                                                key={value}
                                                value={value}
                                            >
                                                {RETENTION_LABELS[value]}
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>

                            <label>
                                Срок хранения, дней
                                <input
                                    inputMode="numeric"
                                    placeholder="Если применимо"
                                    value={draft.retentionDays}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            retentionDays:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                Использование для обучения
                                <select
                                    value={draft.trainingUseStatus}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            trainingUseStatus:
                                                (
                                                    event.target.value as ModelTrainingUseStatus
                                                ),
                                        }))
                                    }}
                                >
                                    {MODEL_TRAINING_USE_STATUSES.map(
                                        (value) => (
                                            <option
                                                key={value}
                                                value={value}
                                            >
                                                {TRAINING_LABELS[value]}
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>

                            <label>
                                Статус стоимости
                                <select
                                    value={draft.pricingStatus}
                                    onChange={(event) => {
                                        const pricingStatus =
                                            (
                                                event.target.value as ModelPricingStatus
                                            )

                                        setDraft((current) =>
                                            normalizeDraftForPricingStatus(
                                                {
                                                    ...current,
                                                    pricingStatus,
                                                },
                                            ),
                                        )
                                    }}
                                >
                                    {MODEL_PRICING_STATUSES.map(
                                        (value) => (
                                            <option
                                                key={value}
                                                value={value}
                                            >
                                                {PRICING_LABELS[value]}
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>
                        </div>
                    </section>
    )
}

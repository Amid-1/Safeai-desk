// ============================================================
// frontend/src/components/admin/models/catalogVersion/CatalogIdentitySection.tsx
// ============================================================
import type { ModelCatalogEntry, ModelLifecycle } from '../../../../api/modelApi'
import { MODEL_LIFECYCLES } from '../../../../api/modelApi'
import type { CatalogSectionProps } from './catalogSectionTypes'
import { LIFECYCLE_LABELS } from './catalogLabels'
import { InfoHint, SectionHeading } from './catalogPresentation'

export function CatalogIdentitySection({draft, setDraft, base}: CatalogSectionProps & { base: ModelCatalogEntry | null }) {
    return (
                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--identity',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Основные данные"
                            hint="Идентификаторы модели и момент вступления версии в силу."
                        />

                        <div className="models-form__grid">
                            <label>
                                <span className="models-label-row">
                                    Ключ модели
                                    <InfoHint text="Уникальное техническое имя модели в каталоге, например openai:gpt-5." />
                                </span>

                                <input
                                    required
                                    maxLength={160}
                                    value={draft.modelKey}
                                    disabled={base !== null}
                                    placeholder="openai:gpt-5"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            modelKey:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                Название
                                <input
                                    required
                                    maxLength={255}
                                    value={draft.displayName}
                                    placeholder="Название для администраторов"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            displayName:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                Провайдер
                                <input
                                    required
                                    maxLength={32}
                                    value={draft.provider}
                                    placeholder="openai"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            provider:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                ID модели у провайдера
                                <input
                                    required
                                    maxLength={100}
                                    value={draft.providerModelId}
                                    placeholder="gpt-5"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            providerModelId:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                Статус каталога
                                <select
                                    value={draft.lifecycle}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            lifecycle:
                                                (
                                                    event.target.value as ModelLifecycle
                                                ),
                                        }))
                                    }}
                                >
                                    {MODEL_LIFECYCLES.map(
                                        (value) => (
                                            <option
                                                key={value}
                                                value={value}
                                            >
                                                {LIFECYCLE_LABELS[value]}
                                            </option>
                                        ),
                                    )}
                                </select>
                            </label>

                            <label>
                                Вступает в силу
                                <input
                                    type="datetime-local"
                                    value={draft.effectiveFrom}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            effectiveFrom:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>
                        </div>
                    </section>
    )
}

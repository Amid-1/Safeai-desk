// ============================================================
// frontend/src/components/admin/models/catalogVersion/CatalogLimitsSection.tsx
// ============================================================
import type { CatalogSectionProps } from './catalogSectionTypes'
import { SectionHeading } from './catalogPresentation'

export function CatalogLimitsSection({draft, setDraft}: CatalogSectionProps) {
    return (
                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--limits',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Лимиты"
                            hint="Ограничения на объём входа и ответа модели."
                            tone="neutral"
                        />

                        <div className="models-form__grid">
                            <label>
                                Входные токены, максимум
                                <input
                                    required
                                    inputMode="numeric"
                                    value={draft.maxInputTokens}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            maxInputTokens:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>

                            <label>
                                Выходные токены, максимум
                                <input
                                    required
                                    inputMode="numeric"
                                    value={draft.maxOutputTokens}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            maxOutputTokens:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>
                        </div>
                    </section>
    )
}

// frontend/src/features/models/ui/catalogVersion/CatalogPricingSection.tsx
// ============================================================
// frontend/src/features/models/ui/catalogVersion/CatalogPricingSection.tsx
// ============================================================
import type { CatalogSectionProps } from './catalogSectionTypes'
import { SectionHeading } from './catalogPresentation'
import { DecimalInput } from '../ModelFormControls'

export function CatalogPricingSection({draft, setDraft}: CatalogSectionProps) {
    return (
                    <section
                        className={[
                            'models-catalog-form__panel',
                            'models-catalog-form__panel--pricing',
                        ].join(' ')}
                    >
                        <SectionHeading
                            title="Тарификация"
                            hint="Версионированные параметры тарификации. Неизвестная стоимость не считается нулевой."
                            tone="pricing"
                        />

                        <label
                            className={[
                                'models-check-row',
                                'models-check-row--strong',
                                'models-catalog-form__pricing-complete',
                            ].join(' ')}
                        >
                            <input
                                type="checkbox"
                                checked={draft.pricingComplete}
                                disabled={
                                    draft.pricingStatus
                                        === 'FREE'
                                    || draft.pricingStatus
                                        === 'UNPRICED'
                                    || draft.pricingStatus
                                        === 'INCOMPLETE'
                                }
                                onChange={(event) => {
                                    setDraft((current) => ({
                                        ...current,
                                        pricingComplete:
                                            event.target.checked,
                                    }))
                                }}
                            />

                            Все варианты тарификации известны
                        </label>

                        <div
                            className={[
                                'models-form__grid',
                                'models-form__pricing-grid',
                                'models-catalog-form__pricing-grid',
                            ].join(' ')}
                        >
                            <DecimalInput
                                label="Вход, USD за 1 млн токенов"
                                value={draft.inputUsdPer1mTokens}
                                onChange={(value) => {
                                    setDraft((current) => ({
                                        ...current,
                                        inputUsdPer1mTokens:
                                            value,
                                    }))
                                }}
                            />

                            <DecimalInput
                                label="Кэшированный вход, USD за 1 млн"
                                value={draft.cachedInputUsdPer1mTokens}
                                onChange={(value) => {
                                    setDraft((current) => ({
                                        ...current,
                                        cachedInputUsdPer1mTokens:
                                            value,
                                    }))
                                }}
                            />

                            <DecimalInput
                                label="Запись кэша, USD за 1 млн"
                                value={draft.cacheWriteInputUsdPer1mTokens}
                                onChange={(value) => {
                                    setDraft((current) => ({
                                        ...current,
                                        cacheWriteInputUsdPer1mTokens:
                                            value,
                                    }))
                                }}
                            />

                            <DecimalInput
                                label="Выход, USD за 1 млн токенов"
                                value={draft.outputUsdPer1mTokens}
                                onChange={(value) => {
                                    setDraft((current) => ({
                                        ...current,
                                        outputUsdPer1mTokens:
                                            value,
                                    }))
                                }}
                            />

                            <label>
                                Версия тарифа
                                <input
                                    maxLength={64}
                                    value={draft.pricingVersion}
                                    placeholder="Например: 2026-08"
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            pricingVersion:
                                                event.target.value,
                                        }))
                                    }}
                                />
                            </label>
                        </div>

                        <details className="models-form__advanced">
                            <summary>
                                Дополнительные параметры тарификации
                            </summary>

                            <label>
                                JSON-параметры
                                <textarea
                                    rows={3}
                                    maxLength={16_000}
                                    value={draft.extraPricingJson}
                                    onChange={(event) => {
                                        setDraft((current) => ({
                                            ...current,
                                            extraPricingJson:
                                                event.target.value,
                                        }))
                                    }}
                                />

                                <small>
                                    Для обычной полной тарификации
                                    оставьте {'{}'}.
                                </small>
                            </label>
                        </details>
                    </section>
    )
}

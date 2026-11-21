// frontend/src/features/models/ui/catalogVersion/CatalogPricingSection.tsx
import { MODEL_PRICING_STATUSES } from '../../../../entities/model/api/modelApi'
import type {ModelPricingStatus} from '../../../../entities/model/api/modelApi'
import type {CatalogSectionProps} from './catalogSectionTypes'
import {PRICING_LABELS} from './catalogLabels'
import {SectionHeading} from './catalogPresentation'
import {normalizeDraftForPricingStatus, parseOptionalDecimal} from '../modelControlPlaneSupport'
import {catalogRubEquivalent} from './catalogRubEquivalent'
import {useCatalogRubQuote} from './useCatalogRubQuote'

const RATES = [
    {key: 'inputUsdPer1mTokens', label: 'Входные токены'},
    {key: 'cachedInputUsdPer1mTokens', label: 'Кэшированный вход'},
    {key: 'cacheWriteInputUsdPer1mTokens', label: 'Запись кэша'},
    {key: 'outputUsdPer1mTokens', label: 'Выходные токены'},
] as const

function dateLabel(value: string) {
    return value.split('-').reverse().join('.')
}

export function CatalogPricingSection({draft, setDraft, active, advancedOpen, setAdvancedOpen}: CatalogSectionProps & {
    active: boolean
    advancedOpen: boolean
    setAdvancedOpen: (open: boolean) => void
}) {
    const {quote, loading, unavailable, refresh} = useCatalogRubQuote(active)
    const fixedPricing = draft.pricingStatus === 'FREE' || draft.pricingStatus === 'UNPRICED'

    return <section className="models-catalog-form__panel models-catalog-form__panel--pricing">
        <SectionHeading title="Тарификация"
            hint="Неизвестная стоимость не считается нулевой. Все тарифы задаются в USD за 1 млн токенов."
            tone="pricing" />

        <div className="models-form__grid">
            <label>
                Статус стоимости
                <select value={draft.pricingStatus} onChange={event => {
                    const pricingStatus = event.target.value as ModelPricingStatus
                    setDraft(current => normalizeDraftForPricingStatus({...current, pricingStatus}))
                }}>
                    {MODEL_PRICING_STATUSES.map(value => <option key={value} value={value}>{PRICING_LABELS[value]}</option>)}
                </select>
            </label>
            <label>
                Версия тарифа
                <input maxLength={64} value={draft.pricingVersion} placeholder="Например: 2026-08"
                    onChange={event => setDraft(current => ({...current, pricingVersion: event.target.value}))} />
            </label>
        </div>

        <label className="models-check-row models-check-row--strong models-catalog-form__pricing-complete">
            <input type="checkbox" checked={draft.pricingComplete} disabled={draft.pricingStatus !== 'CONFIGURED'}
                onChange={event => setDraft(current => ({...current, pricingComplete: event.target.checked}))} />
            Все варианты тарификации известны
        </label>

        <div className="models-catalog-fx">
            <div className="models-catalog-fx__row">
                <div>
                    <strong>Справочный курс Банка России</strong>
                    {quote && <p>1 USD = {quote.rubPerUsd.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '').replace('.', ',')} RUB</p>}
                    {quote && <small>Действует с {dateLabel(quote.effectiveDate)} · получен {dateLabel(quote.quoteDate)}</small>}
                    {loading && <small role="status">Получаем официальный курс…</small>}
                    {unavailable && <small role="status">Курс недоступен. Тарифы можно сохранить в USD.</small>}
                </div>
                <button type="button" disabled={loading} onClick={refresh}>Обновить курс</button>
            </div>
            <small>Рублёвый эквивалент справочный, не сохраняется и не влияет на расчёт расходов.</small>
        </div>

        <div className="models-form__grid models-catalog-form__pricing-grid">
            {RATES.map(({key, label}) => {
                const value = draft[key]
                const rub = quote ? catalogRubEquivalent(value, quote.rubPerUsd) : null
                let valid = true
                try { parseOptionalDecimal(value, label) } catch { valid = false }
                return <div className="models-catalog-rate" key={key}>
                    <label>
                        {label}, USD / 1 млн
                        <input inputMode="decimal" value={value} placeholder="Не задан"
                            disabled={fixedPricing} aria-invalid={!valid}
                            onChange={event => {
                                const text = event.target.value.replace(',', '.')
                                setDraft(current => ({...current, [key]: text}))
                            }} />
                    </label>
                    <small className="models-catalog-rate__equivalent">
                        {!value.trim() ? 'Тариф не задан' : !valid ? 'Проверьте сумму'
                            : rub !== null ? (rub.startsWith('<') ? rub : `≈ ${rub}`) : 'Эквивалент RUB недоступен'}
                    </small>
                </div>
            })}
        </div>

        <details className="models-form__advanced" open={advancedOpen}
            onToggle={event => setAdvancedOpen(event.currentTarget.open)}>
            <summary>Дополнительные параметры тарификации</summary>
            <label>
                JSON-параметры
                <textarea rows={3} maxLength={16_000} value={draft.extraPricingJson}
                    onChange={event => setDraft(current => ({...current, extraPricingJson: event.target.value}))} />
                <small>Для обычной полной тарификации оставьте {'{}'}.</small>
            </label>
        </details>
    </section>
}

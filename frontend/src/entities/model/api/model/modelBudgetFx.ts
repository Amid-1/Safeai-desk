// frontend/src/entities/model/api/model/modelBudgetFx.ts
// Денежная арифметика без IEEE-754; сервер остаётся единственным источником курса и лимитов.
import type {RubBudgetQuote, RubBudgetSnapshot} from './modelTypes'
import {optionalDecimalString, requireInstant, requireObject} from './modelWire'

const SCALE = 10n ** 12n
const LIMIT = 10n ** 30n

function units(value: string): bigint {
    if (!/^\d+(?:\.\d{1,12})?$/.test(value)) throw new Error('Некорректная сумма.')
    const [whole, fraction = ''] = value.split('.')
    if (whole.replace(/^0+/, '').length > 18) throw new Error('Сумма превышает допустимый диапазон.')
    return BigInt(whole) * SCALE + BigInt(fraction.padEnd(12, '0'))
}

function decimal(value: bigint): string {
    if (value < 0n || value >= LIMIT) throw new Error('Сумма превышает допустимый диапазон.')
    const whole = value / SCALE
    const fraction = String(value % SCALE).padStart(12, '0').replace(/0+$/, '')
    return fraction ? `${whole}.${fraction}` : String(whole)
}

export function budgetMoneyEqual(a: string | null, b: string | null): boolean {
    return a === null || b === null ? a === b : units(a) === units(b)
}

export function rubToUsd(amount: string | null, rate: string): string | null {
    if (amount === null) return null
    const rateUnits = units(rate)
    if (rateUnits <= 0n) throw new Error('Некорректный курс.')
    return decimal(units(amount) * SCALE / rateUnits)
}

/** Switching the editor currency may not silently change an existing ceiling. */
export function usdToRub(amount: string | null, rate: string): string | null {
    if (amount === null) return null
    const rateUnits = units(rate)
    if (rateUnits <= 0n) throw new Error('Некорректный курс.')
    const result = decimal((units(amount) * rateUnits + SCALE - 1n) / SCALE)
    if (!budgetMoneyEqual(rubToUsd(result, rate), amount)) {
        throw new Error('Невозможно переключить валюту без изменения лимита. Укажите сумму вручную.')
    }
    return result
}

export function moscowBudgetDate(now = new Date()): string {
    const parts = new Intl.DateTimeFormat('en-CA', {
        timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(now)
    return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)?.value).join('-')
}

function date(value: unknown, path: string): string {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)
        || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString().slice(0, 10) !== value) {
        throw new Error(`${path}: некорректная дата курса`)
    }
    return value
}

export function parseRubBudgetQuote(value: unknown, path = 'rubBudgetQuote'): RubBudgetQuote {
    const raw = requireObject(value, path)
    const rubPerUsd = optionalDecimalString(raw.rubPerUsd, `${path}.rubPerUsd`)
    if (!rubPerUsd || units(rubPerUsd) <= 0n || !/^\d{1,10}(?:\.\d{1,8})?$/.test(rubPerUsd)
        || raw.source !== 'CBR') throw new Error('Некорректная котировка Банка России.')
    const quoteDate = date(raw.quoteDate, `${path}.quoteDate`)
    const effectiveDate = date(raw.effectiveDate, `${path}.effectiveDate`)
    const fetchedAt = requireInstant(raw.fetchedAt, `${path}.fetchedAt`)
    if (effectiveDate > quoteDate || moscowBudgetDate(new Date(fetchedAt)) !== quoteDate
        || Date.parse(quoteDate) - Date.parse(effectiveDate) > 14 * 86400000) {
        throw new Error('Некорректная дата котировки.')
    }
    return {rubPerUsd, quoteDate, effectiveDate, fetchedAt, source: 'CBR'}
}

export function parseRubBudgetSnapshot(value: unknown, maxUsd: string | null, monthlyUsd: string | null): RubBudgetSnapshot {
    const raw = requireObject(value, 'rubBudget')
    const quote = parseRubBudgetQuote(raw, 'rubBudget')
    const maxRequestCostRub = optionalDecimalString(raw.maxRequestCostRub, 'rubBudget.maxRequestCostRub')
    const monthlyBudgetRub = optionalDecimalString(raw.monthlyBudgetRub, 'rubBudget.monthlyBudgetRub')
    if ((maxRequestCostRub === null && monthlyBudgetRub === null)
        || !budgetMoneyEqual(rubToUsd(maxRequestCostRub, quote.rubPerUsd), maxUsd)
        || !budgetMoneyEqual(rubToUsd(monthlyBudgetRub, quote.rubPerUsd), monthlyUsd)) {
        throw new Error('Рублёвый бюджет не соответствует расчётным USD-лимитам.')
    }
    return {...quote, maxRequestCostRub, monthlyBudgetRub}
}

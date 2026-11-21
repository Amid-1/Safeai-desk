// frontend/src/features/models/ui/catalogVersion/catalogRubEquivalent.ts
import {decimalToScale12, parseOptionalDecimal} from '../modelControlPlaneSupport'

/** Display-only conversion; round to kopecks, never change the USD draft. */
export function catalogRubEquivalent(value: string, rate: string): string | null {
    try {
        const amount = parseOptionalDecimal(value, 'Тариф')
        const normalizedRate = parseOptionalDecimal(rate, 'Курс')
        if (amount === null || normalizedRate === null) return null
        const rateUnits = decimalToScale12(normalizedRate)
        if (rateUnits <= 0n) return null
        const product = decimalToScale12(amount) * rateUnits
        const divisor = 10n ** 22n
        const kopecks = (product + divisor / 2n) / divisor
        if (product > 0n && kopecks === 0n) return '< 0,01 ₽'
        const whole = new Intl.NumberFormat('ru-RU').format(kopecks / 100n)
        return `${whole},${String(kopecks % 100n).padStart(2, '0')} ₽`
    } catch {
        return null
    }
}

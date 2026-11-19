// frontend/src/shared/lib/format.ts
// ============================================================
// frontend/src/shared/lib/format.ts
// ============================================================
const INTEGER_PATTERN =
    /^\d+$/

const DECIMAL_PATTERN =
    /^\d+(?:\.\d+)?$/

const INTEGER_FORMATTER =
    new Intl.NumberFormat(
        'ru-RU',
        {
            maximumFractionDigits: 0,
        },
    )

const DATE_TIME_FORMATTER =
    new Intl.DateTimeFormat(
        'ru-RU',
        {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            timeZoneName: 'short',
        },
    )

export function formatDateTime(
    value: string | null | undefined,
): string {
    if (!value) {
        return '—'
    }

    const date =
        new Date(value)

    if (
        Number.isNaN(
            date.getTime(),
        )
    ) {
        return 'Некорректная дата'
    }

    return DATE_TIME_FORMATTER.format(
        date,
    )
}

export function formatIntegerValue(
    value: string | number | null | undefined,
): string {
    if (
        value === null
        || value === undefined
        || value === ''
    ) {
        return '—'
    }

    const normalized =
        typeof value === 'number'
            ? (
                Number.isSafeInteger(value)
                && value >= 0
                    ? String(value)
                    : ''
            )
            : value.trim()

    if (
        !normalized
        || !INTEGER_PATTERN.test(
            normalized,
        )
    ) {
        return 'Некорректное значение'
    }

    try {
        return INTEGER_FORMATTER.format(
            BigInt(normalized),
        )
    } catch {
        return 'Некорректное значение'
    }
}

export function formatUsd(
    value: string | number | null | undefined,
): string {
    if (
        value === null
        || value === undefined
        || value === ''
    ) {
        return 'Не указана'
    }

    const normalized =
        normalizeDecimal(
            value,
        )

    if (!normalized) {
        return 'Некорректное значение'
    }

    const [
        rawIntegerPart,
        rawFraction = '',
    ] = normalized.split('.')

    const integerPart =
        rawIntegerPart ?? '0'

    const fractionWithoutTrailingZeroes =
        rawFraction.replace(
            /0+$/,
            '',
        )

    const groupedInteger =
        INTEGER_FORMATTER.format(
            BigInt(integerPart),
        )

    // Keep all significant decimal digits, including sub-micro-dollar prices.
    // Only presentation changes; no rounding or conversion through Number.
    return `${groupedInteger}${fractionWithoutTrailingZeroes ? ',' + fractionWithoutTrailingZeroes.padEnd(2, '0') : ''} USD`
}

function normalizeDecimal(
    value: string | number,
): string | null {
    let normalized: string

    if (
        typeof value === 'number'
    ) {
        if (
            !Number.isFinite(value)
            || value < 0
        ) {
            return null
        }

        normalized = String(value)
        if (normalized.includes('e')) {
            const [mantissa, exponent] = normalized.split('e')
            const [whole, fraction = ''] = mantissa.split('.')
            const digits = whole + fraction
            const position = whole.length + Number(exponent)
            normalized = position <= 0 ? '0.' + '0'.repeat(-position) + digits
                : position >= digits.length ? digits + '0'.repeat(position - digits.length)
                    : digits.slice(0, position) + '.' + digits.slice(position)
        }
    } else {
        normalized =
            value.trim()
    }

    if (
        !DECIMAL_PATTERN.test(
            normalized,
        )
    ) {
        return null
    }

    const [
        rawIntegerValue,
        fraction,
    ] = normalized.split('.')

    const rawInteger =
        rawIntegerValue ?? '0'

    const integer =
        rawInteger.replace(
            /^0+(?=\d)/,
            '',
        )

    return fraction === undefined
        ? integer
        : `${integer}.${fraction}`
}

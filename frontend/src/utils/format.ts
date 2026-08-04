const DATE_ONLY_PATTERN =
    /^(\d{4})-(\d{2})-(\d{2})$/

const INTEGER_PATTERN = /^\d+$/
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

    const date = new Date(value)

    if (
        Number.isNaN(date.getTime())
    ) {
        return 'Некорректная дата'
    }

    return DATE_TIME_FORMATTER.format(
        date,
    )
}

export function formatDate(
    value: string | null | undefined,
): string {
    if (!value) {
        return '—'
    }

    const dateOnlyMatch =
        DATE_ONLY_PATTERN.exec(value)

    if (dateOnlyMatch) {
        const [, year, month, day] =
            dateOnlyMatch

        return `${day}.${month}.${year}`
    }

    const date = new Date(value)

    if (
        Number.isNaN(date.getTime())
    ) {
        return 'Некорректная дата'
    }

    return date.toLocaleDateString(
        'ru-RU',
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
            : value

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
        return '—'
    }

    const normalized =
        normalizeDecimal(value)

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

    const isZero =
        /^0+$/.test(integerPart)
        && fractionWithoutTrailingZeroes
            .length === 0

    if (isZero) {
        return '$0.0000'
    }

    const lessThanMicroDollar =
        /^0+$/.test(integerPart)
        && (
            fractionWithoutTrailingZeroes
                .slice(0, 6)
                .replace(/0/g, '')
                .length === 0
        )

    if (lessThanMicroDollar) {
        return '< $0.000001'
    }

    const groupedInteger =
        INTEGER_FORMATTER.format(
            BigInt(integerPart),
        ).replace(
            /[\u00a0\u202f]/g,
            ',',
        )

    const displayedFraction =
        fractionWithoutTrailingZeroes
            .slice(0, 12)
            .padEnd(4, '0')

    return displayedFraction
        ? `$${groupedInteger}.${displayedFraction}`
        : `$${groupedInteger}.0000`
}

function normalizeDecimal(
    value: string | number,
): string | null {
    let normalized: string

    if (typeof value === 'number') {
        if (
            !Number.isFinite(value)
            || value < 0
        ) {
            return null
        }

        normalized = value
            .toFixed(12)
            .replace(/0+$/, '')
            .replace(/\.$/, '')

        if (
            !DECIMAL_PATTERN.test(
                normalized,
            )
        ) {
            return null
        }
    } else {
        normalized = value.trim()

        if (
            !DECIMAL_PATTERN.test(
                normalized,
            )
        ) {
            return null
        }
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

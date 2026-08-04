import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    formatIntegerValue,
    formatUsd,
} from './format'

describe('formatUsd', () => {
    it('null не отображается как ноль', () => {
        expect(formatUsd(null))
            .toBe('—')
    })

    it('нулевая подтверждённая сумма отображается как $0.0000', () => {
        expect(formatUsd('0'))
            .toBe('$0.0000')
    })

    it('micro-cost не округляется визуально до нуля', () => {
        expect(
            formatUsd(
                '0.000000123456',
            ),
        ).toBe('< $0.000001')

        expect(
            formatUsd(
                '0.000001',
            ),
        ).toBe('$0.000001')
    })

    it('отрицательное и NaN считаются invalid', () => {
        expect(formatUsd(-1))
            .toBe(
                'Некорректное значение',
            )

        expect(formatUsd(Number.NaN))
            .toBe(
                'Некорректное значение',
            )
    })

    it('большие token counters форматируются через BigInt string', () => {
        expect(
            formatIntegerValue(
                '900719925474099312345',
            ),
        ).not.toBe(
            'Некорректное значение',
        )
    })
})

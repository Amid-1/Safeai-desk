import {describe, expect, it} from 'vitest'
import {parsePolicyMoney, parsePolicyTokenLimit} from './policyNumericFields'

describe('policy numeric input', () => {
    it.each(['0', '-1', '1.5', '1,5', '1e3', '0x100', '2147483648', '+1', '1 000', 'Infinity', 'NaN'])(
        'rejects non-positive, malformed or overflowing token limit %s',
        value => expect(() => parsePolicyTokenLimit(value, 'Tokens')).toThrow(),
    )
    it.each([['', null], ['  ', null], ['1', 1], ['64000', 64000], ['2147483647', 2147483647]] as const)(
        'accepts optional bounded integer %s',
        (value, expected) => expect(parsePolicyTokenLimit(value, 'Tokens')).toBe(expected),
    )
    it.each(['-1', '+1', '1e-3', '0x100', '$100', '1 000', '1.2,3', '1,,2', '1,', ',5',
        '1000000000000000000', '0,0000000000001'])(
        'rejects malformed or overflowing money %s',
        value => expect(() => parsePolicyMoney(value, 'USD')).toThrow(),
    )
    it.each([
        ['', null], ['0', '0'], ['100', '100'], ['0,50', '0.50'], ['0.50', '0.50'],
        ['0,000000000001', '0.000000000001'],
        ['999999999999999999,123456789012', '999999999999999999.123456789012'],
    ] as const)('preserves exact decimal money %s', (value, expected) => {
        expect(parsePolicyMoney(value, 'USD')).toBe(expected)
    })
})

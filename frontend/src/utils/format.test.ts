// ============================================================
// frontend/src/utils/format.test.ts
// ============================================================
import {
    describe,
    expect,
    it,
} from 'vitest'

import {
    formatDateTime,
    formatIntegerValue,
    formatUsd,
} from './format'

describe(
    'formatDateTime',
    () => {
        it(
            'пустое значение отображает как отсутствие данных',
            () => {
                expect(
                    formatDateTime(null),
                ).toBe('—')

                expect(
                    formatDateTime(undefined),
                ).toBe('—')

                expect(
                    formatDateTime(''),
                ).toBe('—')
            },
        )

        it(
            'некорректную дату не отображает как валидную',
            () => {
                expect(
                    formatDateTime(
                        'not-a-date',
                    ),
                ).toBe(
                    'Некорректная дата',
                )
            },
        )

        it(
            'валидный ISO instant форматирует для пользователя',
            () => {
                const result =
                    formatDateTime(
                        '2026-09-05T09:00:00Z',
                    )

                expect(result)
                    .not.toBe('—')

                expect(result)
                    .not.toBe(
                        'Некорректная дата',
                    )
            },
        )
    },
)

describe(
    'formatIntegerValue',
    () => {
        it(
            'null не отображается как ноль',
            () => {
                expect(
                    formatIntegerValue(
                        null,
                    ),
                ).toBe('—')
            },
        )

        it(
            'форматирует безопасное целое число',
            () => {
                expect(
                    formatIntegerValue(
                        1234,
                    ),
                ).not.toBe(
                    'Некорректное значение',
                )
            },
        )

        it(
            'большие счётчики форматирует через BigInt string',
            () => {
                expect(
                    formatIntegerValue(
                        '900719925474099312345',
                    ),
                ).not.toBe(
                    'Некорректное значение',
                )
            },
        )

        it(
            'отклоняет отрицательные и дробные значения',
            () => {
                expect(
                    formatIntegerValue(
                        -1,
                    ),
                ).toBe(
                    'Некорректное значение',
                )

                expect(
                    formatIntegerValue(
                        1.5,
                    ),
                ).toBe(
                    'Некорректное значение',
                )
            },
        )
    },
)

describe(
    'formatUsd',
    () => {
        it(
            'null не отображается как ноль',
            () => {
                expect(
                    formatUsd(null),
                ).toBe('—')
            },
        )

        it(
            'нулевая подтверждённая сумма отображается как $0.0000',
            () => {
                expect(
                    formatUsd('0'),
                ).toBe(
                    '$0.0000',
                )
            },
        )

        it(
            'micro-cost не округляется визуально до нуля',
            () => {
                expect(
                    formatUsd(
                        '0.000000123456',
                    ),
                ).toBe(
                    '< $0.000001',
                )

                expect(
                    formatUsd(
                        '0.000001',
                    ),
                ).toBe(
                    '$0.000001',
                )
            },
        )

        it(
            'сохраняет точность exact decimal string',
            () => {
                expect(
                    formatUsd(
                        '2.123456789012',
                    ),
                ).toBe(
                    '$2.123456789012',
                )
            },
        )

        it(
            'удаляет незначащие нули, но оставляет минимум четыре знака',
            () => {
                expect(
                    formatUsd(
                        '12.500000000000',
                    ),
                ).toBe(
                    '$12.5000',
                )
            },
        )

        it(
            'целую ненулевую сумму отображает минимум с четырьмя знаками',
            () => {
                expect(
                    formatUsd(
                        '12',
                    ),
                ).toBe(
                    '$12.0000',
                )
            },
        )

        it(
            'отрицательное и NaN считаются invalid',
            () => {
                expect(
                    formatUsd(-1),
                ).toBe(
                    'Некорректное значение',
                )

                expect(
                    formatUsd(
                        Number.NaN,
                    ),
                ).toBe(
                    'Некорректное значение',
                )
            },
        )
    },
)

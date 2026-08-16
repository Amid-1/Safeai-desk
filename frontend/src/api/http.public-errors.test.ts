import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    ApiError,
    getApiErrorMessage,
    getApiErrorPresentation,
} from './http'

describe(
    'getApiErrorPresentation',
    () => {
        it(
            'показывает безопасное сообщение backend для CONFLICT без Request ID',
            () => {
                const error =
                    new ApiError(
                        'Документ с таким названием уже существует.',
                        {
                            status: 409,
                            error:
                                'CONFLICT',
                            message:
                                'Документ с таким названием уже существует.',
                            requestId:
                                'request-409',
                        },
                        409,
                    )

                expect(
                    getApiErrorPresentation(
                        error,
                        'Не удалось загрузить файл.',
                    ),
                ).toEqual({
                    message:
                        'Документ с таким названием уже существует.',
                })

                expect(
                    getApiErrorMessage(
                        error,
                        'Не удалось загрузить файл.',
                    ),
                ).toBe(
                    'Документ с таким названием уже существует.',
                )
            },
        )

        it(
            'не показывает Request ID для ожидаемой BAD_REQUEST ошибки',
            () => {
                const error =
                    new ApiError(
                        'Расширение файла не соответствует его содержимому.',
                        {
                            status: 400,
                            error:
                                'BAD_REQUEST',
                            message:
                                'Расширение файла не соответствует его содержимому.',
                            requestId:
                                'request-400',
                        },
                        400,
                    )

                expect(
                    getApiErrorPresentation(
                        error,
                        'Не удалось загрузить файл.',
                    ),
                ).toEqual({
                    message:
                        'Расширение файла не соответствует его содержимому.',
                })
            },
        )

        it(
            'для 500 скрывает внутреннее сообщение и показывает Request ID',
            () => {
                const error =
                    new ApiError(
                        'Sensitive internal details',
                        {
                            status: 500,
                            error:
                                'INTERNAL_ERROR',
                            message:
                                'Sensitive internal details',
                            requestId:
                                'request-500',
                        },
                        500,
                    )

                expect(
                    getApiErrorPresentation(
                        error,
                        'Не удалось выполнить операцию.',
                    ),
                ).toEqual({
                    message:
                        'Не удалось выполнить операцию.',
                    requestId:
                        'request-500',
                })

                expect(
                    getApiErrorMessage(
                        error,
                        'Не удалось выполнить операцию.',
                    ),
                ).toBe(
                    'Не удалось выполнить операцию. Request ID: request-500',
                )
            },
        )

        it(
            'показывает Request ID для transport ошибки status 0',
            () => {
                const error =
                    new ApiError(
                        'Не удалось связаться с сервером',
                        {
                            status: 0,
                            error:
                                'NETWORK_ERROR',
                            message:
                                'Не удалось связаться с сервером',
                            requestId:
                                'request-network',
                        },
                        0,
                    )

                expect(
                    getApiErrorPresentation(
                        error,
                        'Не удалось выполнить операцию.',
                    ),
                ).toEqual({
                    message:
                        'Не удалось выполнить операцию.',
                    requestId:
                        'request-network',
                })
            },
        )
    },
)

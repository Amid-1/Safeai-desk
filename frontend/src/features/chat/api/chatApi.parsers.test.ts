// frontend/src/features/chat/api/chatApi.parsers.test.ts
// Fail-closed проверка всех публичных парсеров, исторических DTO и денежных значений.

import { describe, expect, it } from 'vitest'
import {
    parseAnswerPassport, parseChat, parseChatCapabilities, parseChatDetails,
    parseChatMessage, parseChatSliceResponse, parseChatTurnStatus,
    parseSendMessageResponse,
} from './chatApi'
import {
    IDS, LATER, assistantMessage, capabilities, chat, passport,
    slice, succeeded, turn, userMessage,
} from '../../../test/fixtures/chatApiFixtures'

function without<T extends object>(object: T, ...keys: (keyof T)[]): Record<string, unknown> {
    const copy = { ...object } as Record<string, unknown>
    for (const key of keys) delete copy[String(key)]
    return copy
}

describe('chatApi: Chat, ChatDetails и Slice', () => {
    it('возвращает валидный Chat без потери полей', () => {
        expect(parseChat(chat())).toEqual(chat())
    })

    it('возвращает историю сообщений с сохранением порядка', () => {
        const messages = [userMessage(), assistantMessage()]
        expect(parseChatDetails({ ...chat(), messages }).messages).toEqual(messages)
    })

    it('использует явный путь до некорректного вложенного сообщения', () => {
        expect(() => parseChatDetails({ ...chat(), messages: [userMessage(), null] }))
            .toThrow('chatDetails.messages[1]')
    })

    it('не принимает ChatDetails с отсутствующим массивом сообщений', () => {
        expect(() => parseChatDetails(chat())).toThrow('messages')
    })

    it.each([
        ['null', null],
        ['массив', []],
        ['некорректный UUID', { ...chat(), id: 'not-a-uuid' }],
        ['пустой title', { ...chat(), title: '' }],
        ['слишком длинный title', { ...chat(), title: 'x'.repeat(256) }],
        ['отсутствует время создания', without(chat(), 'createdAt')],
        ['некорректная дата', { ...chat(), updatedAt: 'yesterday' }],
    ])('отклоняет некорректный Chat: %s', (_label, value) => {
        expect(() => parseChat(value)).toThrow()
    })

    it('сохраняет поля собственной модели Slice', () => {
        expect(parseChatSliceResponse(slice([chat()], 2), parseChat)).toEqual(slice([chat()], 2))
    })

    it.each([
        ['нет content', without(slice([]), 'content')],
        ['content не массив', { ...slice([]), content: {} }],
        ['page отрицательный', { ...slice([]), page: -1 }],
        ['page не целый', { ...slice([]), page: 0.5 }],
        ['page выходит за safe integer', { ...slice([]), page: Number.MAX_SAFE_INTEGER + 1 }],
        ['size не положительный', { ...slice([]), size: 0 }],
        ['first не соответствует page', { ...slice([]), first: false }],
        ['last противоречит hasNext', { ...slice([]), last: false }],
        ['hasPrevious не соответствует page', { ...slice([]), hasPrevious: true }],
        ['hasNext не boolean', { ...slice([]), hasNext: 'false' }],
    ])('отклоняет некорректный Slice: %s', (_label, value) => {
        expect(() => parseChatSliceResponse(value, parseChat)).toThrow()
    })

    it('не пропускает невалидный вложенный элемент Slice', () => {
        expect(() => parseChatSliceResponse(slice([chat(), { ...chat(), id: '?' }]), parseChat))
            .toThrow('chatSlice.content[1]')
    })
})

describe('chatApi: Capabilities', () => {
    it('разбирает все четыре лимита', () => {
        expect(parseChatCapabilities(capabilities())).toEqual(capabilities())
    })

    it.each(['maxMessageChars', 'maxChatPageSize', 'maxMessagePageSize', 'detailsMessageLimit'] as const)(
        'не принимает отсутствующее/нулевое/дробное/небезопасное значение %s', field => {
            for (const invalid of [undefined, null, 0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
                expect(() => parseChatCapabilities({ ...capabilities(), [field]: invalid }))
                    .toThrow(`chatCapabilities.${field}`)
            }
        },
    )
})

describe('chatApi: ChatMessage и совместимость DTO', () => {
    it('разбирает сообщения обеих ролей без потери usage и стоимости', () => {
        expect(parseChatMessage(userMessage())).toEqual(userMessage())
        expect(parseChatMessage(assistantMessage())).toEqual(assistantMessage())
    })

    it('сохраняет 0 как подтверждённую бесплатность, а null как неизвестную стоимость', () => {
        expect(parseChatMessage(assistantMessage({ costUsd: '0.000', pricingStatus: 'FREE' })).costUsd)
            .toBe('0.000')
        expect(parseChatMessage(assistantMessage({ costUsd: null, pricingStatus: 'UNPRICED' })).costUsd)
            .toBeNull()
    })

    it('допускает пустой ответ ассистента, но не пустое пользовательское сообщение', () => {
        expect(parseChatMessage(assistantMessage({ content: '' })).content).toBe('')
        expect(() => parseChatMessage(userMessage({ content: '' }))).toThrow()
    })

    it.each(['USER', 'ASSISTANT', 'SYSTEM'] as const)(
        'сохраняет допустимую роль %s', role => {
            expect(parseChatMessage(userMessage({ role })).role).toBe(role)
        },
    )

    it.each(['PENDING', 'COMPLETED', 'FAILED'] as const)(
        'сохраняет статус сообщения %s', status => {
            expect(parseChatMessage(assistantMessage({ status })).status).toBe(status)
        },
    )

    it.each(['NOT_APPLICABLE', 'AVAILABLE', 'MISSING', 'PARTIAL'] as const)(
        'не подменяет явно заданный usageStatus=%s', usageStatus => {
            expect(parseChatMessage(assistantMessage({ usageStatus })).usageStatus).toBe(usageStatus)
        },
    )

    it.each(['NOT_APPLICABLE', 'PRICED', 'FREE', 'UNPRICED', 'CALCULATION_FAILED'] as const)(
        'не подменяет явно заданный pricingStatus=%s', pricingStatus => {
            expect(parseChatMessage(assistantMessage({ pricingStatus })).pricingStatus).toBe(pricingStatus)
        },
    )

    it.each([
        ['оба токена отсутствуют', null, null, 'MISSING'],
        ['нет входных токенов', null, 2, 'PARTIAL'],
        ['нет выходных токенов', 2, null, 'PARTIAL'],
        ['оба токена есть, включая нули', 0, 0, 'AVAILABLE'],
    ] as const)('определяет legacy usage: %s', (_label, inputTokens, outputTokens, status) => {
        const dto = without(assistantMessage({ inputTokens, outputTokens }), 'usageStatus')
        expect(parseChatMessage(dto).usageStatus).toBe(status)
    })

    it('в legacy DTO USER/SYSTEM всегда оставляет usageStatus NOT_APPLICABLE', () => {
        for (const role of ['USER', 'SYSTEM'] as const) {
            expect(parseChatMessage(without(userMessage({ role }), 'usageStatus')).usageStatus)
                .toBe('NOT_APPLICABLE')
        }
    })

    it.each([
        [null, 'UNPRICED'],
        ['0', 'FREE'],
        ['-0.000', 'FREE'],
        ['000.000', 'FREE'],
        ['0.000000000000000000001', 'PRICED'],
        [`0.${'0'.repeat(400)}1`, 'PRICED'],
        ['2.500000000000000000001', 'PRICED'],
    ] as const)('определяет legacy стоимость %s без потери точности', (costUsd, status) => {
        const dto = without(assistantMessage({ costUsd }), 'pricingStatus')
        expect(parseChatMessage(dto).pricingStatus).toBe(status)
    })

    it('для USER без pricingStatus сохраняет NOT_APPLICABLE даже при нулевой стоимости', () => {
        const dto = without(userMessage({ costUsd: '0' }), 'pricingStatus')
        expect(parseChatMessage(dto).pricingStatus).toBe('NOT_APPLICABLE')
    })

    it('сохраняет историческое число BigDecimal в строковом costUsd без вычислений', () => {
        const parsed = parseChatMessage({ ...assistantMessage(), costUsd: 0.125 })
        expect(parsed.costUsd).toBe('0.125')
    })

    it.each([
        ['неизвестная роль', { role: 'OWNER' }],
        ['неизвестный статус', { status: 'SUCCEEDED' }],
        ['неизвестный usageStatus', { usageStatus: 'ESTIMATED' }],
        ['неизвестный pricingStatus', { pricingStatus: 'UNKNOWN' }],
        ['NaN токенов', { inputTokens: Number.NaN }],
        ['отрицательные токены', { outputTokens: -1 }],
        ['небезопасные токены', { inputTokens: Number.MAX_SAFE_INTEGER + 1 }],
        ['некорректная стоимость', { costUsd: '1e-20' }],
        ['несуществующий replyToMessageId', { replyToMessageId: 'wrong' }],
        ['пустой ID', { id: '' }],
        ['длинный providerRequestId', { providerRequestId: 'x'.repeat(256) }],
        ['некорректное время', { createdAt: 'wrong' }],
    ])('fail-closed при некорректном поле сообщения: %s', (_label, change) => {
        expect(() => parseChatMessage({ ...assistantMessage(), ...change })).toThrow()
    })
})

describe('chatApi: SendMessageResponse и durable turn', () => {
    it('проверяет идентификаторы и встроенный паспорт ответа', () => {
        const result = parseSendMessageResponse(succeeded({ answerPassport: passport(), replay: true }))
        expect(result.state).toBe('SUCCEEDED')
        expect(result.replay).toBe(true)
        expect(result.answerPassport?.citations).toHaveLength(1)
        expect(result.userMessage.id).toBe(IDS.userMessage)
        expect(result.assistantMessage.id).toBe(IDS.assistantMessage)
    })

    it.each(['NEW', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'AMBIGUOUS'] as const)(
        'не теряет допустимое состояние turn: %s', state => {
            expect(parseChatTurnStatus(turn({ state })).state).toBe(state)
        },
    )

    it('AMBIGUOUS не превращается в SUCCEEDED и сохраняет outcomeAmbiguous=true', () => {
        const response = parseChatTurnStatus(turn({ state: 'AMBIGUOUS', outcomeAmbiguous: true }))
        expect(response.state).toBe('AMBIGUOUS')
        expect(response.outcomeAmbiguous).toBe(true)
        expect(response.assistantMessage).toBeNull()
    })

    it('FAILED сохраняет код сбоя без создания ответа', () => {
        const response = parseChatTurnStatus(turn({ state: 'FAILED', failureCode: 'AI_TIMEOUT' }))
        expect(response.failureCode).toBe('AI_TIMEOUT')
        expect(response.assistantMessage).toBeNull()
    })

    it('SUCCEEDED turn сохраняет пользовательское сообщение и ответ', () => {
        const response = parseChatTurnStatus(turn({
            state: 'SUCCEEDED',
            completedAt: LATER,
            assistantMessage: assistantMessage(),
        }))
        expect(response.userMessage?.role).toBe('USER')
        expect(response.assistantMessage?.role).toBe('ASSISTANT')
        expect(response.completedAt).toBe(LATER)
    })

    it.each([
        ['неуспешный HTTP state', { state: 'AMBIGUOUS' }],
        ['нет replay', { replay: undefined }],
        ['replay строковый', { replay: 'false' }],
        ['плохой turnId', { turnId: 'wrong' }],
        ['нет assistantMessage', { assistantMessage: null }],
        ['неверная вложенная роль', { userMessage: { ...userMessage(), role: 'WRONG' } }],
        ['неверная дата завершения', { completedAt: '2026-99-99' }],
    ])('отклоняет некорректный SendMessageResponse: %s', (_label, change) => {
        expect(() => parseSendMessageResponse({ ...succeeded(), ...change })).toThrow()
    })

    it.each([
        ['неизвестный state', { state: 'RETRYABLE' }],
        ['outcomeAmbiguous строка', { outcomeAmbiguous: 'true' }],
        ['неверный clientRequestId', { clientRequestId: null }],
        ['неверный operationId', { providerOperationId: '' }],
        ['invalid lease date', { leaseUntil: 'tomorrow' }],
        ['плохое вложенное сообщение', { userMessage: { ...userMessage(), role: 'OWNER' } }],
    ])('отклоняет некорректный ChatTurnStatus: %s', (_label, change) => {
        expect(() => parseChatTurnStatus({ ...turn(), ...change })).toThrow()
    })
})

describe('chatApi: AnswerPassport / RAG', () => {
    it('сохраняет полный паспорт, версии, идентификаторы чанков и SHA-256', () => {
        const value = passport()
        expect(parseAnswerPassport(value)).toEqual(value)
    })

    it('принимает KNOWLEDGE_ONLY и null для необязательного modelRouteDecisionId', () => {
        const parsed = parseAnswerPassport(passport({ knowledgeMode: 'KNOWLEDGE_ONLY' }))
        expect(parsed.knowledgeMode).toBe('KNOWLEDGE_ONLY')
        expect(parsed.modelRouteDecisionId).toBeNull()
    })

    it('для исторического паспорта без modelRouteDecisionId сохраняет null', () => {
        const dto = without(passport(), 'modelRouteDecisionId')
        expect(parseAnswerPassport(dto).modelRouteDecisionId).toBeNull()
    })

    it.each([
        ['GENERAL mode', { knowledgeMode: 'GENERAL' }],
        ['отсутствие цитат', { citations: undefined }],
        ['цитаты объектом', { citations: {} }],
        ['неверный SHA тип', { answerSha256: 123 }],
        ['булево доказательство строкой', { evidenceSufficient: 'true' }],
        ['булево цитаты строкой', { citationsValid: 'false' }],
        ['неверный UUID версии', { citations: [{ ...passport().citations[0], documentVersionId: 'x' }] }],
        ['отрицательная страница', { citations: [{ ...passport().citations[0], pageFrom: -1 }] }],
        ['плохой chunkOrdinal', { citations: [{ ...passport().citations[0], chunkOrdinal: NaN }] }],
        ['некорректная дата', { createdAt: 'not-an-instant' }],
    ])('отклоняет некорректный AnswerPassport: %s', (_label, change) => {
        expect(() => parseAnswerPassport({ ...passport(), ...change })).toThrow()
    })

    it('не заменяет явно известные нулевые индексы на неизвестные значения', () => {
        const parsed = parseAnswerPassport(passport({ citations: [{
            ...passport().citations[0]!, versionNumber: 0, chunkOrdinal: 0,
            pageFrom: 0, pageTo: 0,
        }] }))
        expect(parsed.citations[0]?.chunkOrdinal).toBe(0)
        expect(parsed.citations[0]?.pageFrom).toBe(0)
    })

    it('не меняет исходную структуру паспорта при полном разборе', () => {
        const original = passport({ modelRouteDecisionId: IDS.operation })
        const decoded = parseAnswerPassport(original)
        expect(decoded.modelRouteDecisionId).toBe(IDS.operation)
        expect(decoded.citations[0]?.documentName).toBe('Документ')
        expect(decoded.citations[0]?.pageTo).toBe(2)
    })
})

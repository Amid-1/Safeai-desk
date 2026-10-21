// frontend/src/api/chat/chatTurnParsers.ts
// Результаты отправки и durable turn; AMBIGUOUS не считается успешным завершением.

import {
    contractError, expectEnum, expectInstant, expectNullableInstant,
    expectNullableString, expectRecord, expectString, expectUuid,
} from '../runtime'
import type { SendMessageResponse, ChatTurnStatus } from './chatTypes'
import { TURN_STATES } from './chatEnums'
import { parseChatMessage } from './chatMessageParser'
import { parseAnswerPassport } from './chatPassportParser'
import { expectBoolean } from './chatValidation'

export function parseSendMessageResponse(
    value: unknown,
    field = 'sendMessageResponse',
): SendMessageResponse {
    const record = expectRecord(value, field)
    const state = expectString(
        record.state,
        `${field}.state`,
        { maxLength: 32 },
    )

    if (state !== 'SUCCEEDED') {
        throw contractError(
            `${field}.state должен быть SUCCEEDED для успешного HTTP-ответа`,
        )
    }

    return {
        chatId: expectUuid(
            record.chatId,
            `${field}.chatId`,
        ),
        turnId: expectUuid(
            record.turnId,
            `${field}.turnId`,
        ),
        clientRequestId: expectUuid(
            record.clientRequestId,
            `${field}.clientRequestId`,
        ),
        providerOperationId: expectUuid(
            record.providerOperationId,
            `${field}.providerOperationId`,
        ),
        state,
        replay: expectBoolean(
            record.replay,
            `${field}.replay`,
        ),
        userMessage: parseChatMessage(
            record.userMessage,
            `${field}.userMessage`,
        ),
        assistantMessage: parseChatMessage(
            record.assistantMessage,
            `${field}.assistantMessage`,
        ),
        chatUpdatedAt: expectNullableInstant(
            record.chatUpdatedAt ?? null,
            `${field}.chatUpdatedAt`,
        ),
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        completedAt: expectInstant(
            record.completedAt,
            `${field}.completedAt`,
        ),
        answerPassport: record.answerPassport == null
            ? null
            : parseAnswerPassport(
                record.answerPassport,
                `${field}.answerPassport`,
            ),
    }
}

export function parseChatTurnStatus(
    value: unknown,
    field = 'chatTurnStatus',
): ChatTurnStatus {
    const record = expectRecord(value, field)

    return {
        chatId: expectUuid(
            record.chatId,
            `${field}.chatId`,
        ),
        turnId: expectUuid(
            record.turnId,
            `${field}.turnId`,
        ),
        clientRequestId: expectUuid(
            record.clientRequestId,
            `${field}.clientRequestId`,
        ),
        providerOperationId: expectUuid(
            record.providerOperationId,
            `${field}.providerOperationId`,
        ),
        state: expectEnum(
            record.state,
            `${field}.state`,
            TURN_STATES,
        ),
        provider: expectNullableString(
            record.provider ?? null,
            `${field}.provider`,
            { maxLength: 100 },
        ),
        requestedModel: expectNullableString(
            record.requestedModel ?? null,
            `${field}.requestedModel`,
            { maxLength: 100 },
        ),
        resolvedModel: expectNullableString(
            record.resolvedModel ?? null,
            `${field}.resolvedModel`,
            { maxLength: 100 },
        ),
        providerRequestId: expectNullableString(
            record.providerRequestId ?? null,
            `${field}.providerRequestId`,
            { maxLength: 255 },
        ),
        providerErrorType: expectNullableString(
            record.providerErrorType ?? null,
            `${field}.providerErrorType`,
            { maxLength: 100 },
        ),
        failureCode: expectNullableString(
            record.failureCode ?? null,
            `${field}.failureCode`,
            { maxLength: 100 },
        ),
        outcomeAmbiguous: expectBoolean(
            record.outcomeAmbiguous,
            `${field}.outcomeAmbiguous`,
        ),
        leaseUntil: expectNullableInstant(
            record.leaseUntil ?? null,
            `${field}.leaseUntil`,
        ),
        providerCallStartedAt: expectNullableInstant(
            record.providerCallStartedAt ?? null,
            `${field}.providerCallStartedAt`,
        ),
        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
        updatedAt: expectInstant(
            record.updatedAt,
            `${field}.updatedAt`,
        ),
        completedAt: expectNullableInstant(
            record.completedAt ?? null,
            `${field}.completedAt`,
        ),
        userMessage: record.userMessage == null
            ? null
            : parseChatMessage(
                record.userMessage,
                `${field}.userMessage`,
            ),
        assistantMessage: record.assistantMessage == null
            ? null
            : parseChatMessage(
                record.assistantMessage,
                `${field}.assistantMessage`,
            ),
    }
}

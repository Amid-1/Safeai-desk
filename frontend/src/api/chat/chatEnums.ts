// frontend/src/api/chat/chatEnums.ts
// Допустимые перечисления wire-контракта; неизвестные значения отклоняются.

import type {
    AiResponseStatus,
    ChatMessageRole,
    ChatMessageStatus,
    PricingStatus,
    UsageStatus,
} from '../types'

export const CHAT_ROLES: readonly ChatMessageRole[] = [
    'USER',
    'ASSISTANT',
    'SYSTEM',
]

export const CHAT_STATUSES: readonly ChatMessageStatus[] = [
    'PENDING',
    'COMPLETED',
    'FAILED',
]

export const AI_RESPONSE_STATUSES: readonly AiResponseStatus[] = [
    'COMPLETED',
    'REFUSED',
    'INCOMPLETE',
]

export const USAGE_STATUSES: readonly UsageStatus[] = [
    'NOT_APPLICABLE',
    'AVAILABLE',
    'MISSING',
    'PARTIAL',
]

export const PRICING_STATUSES: readonly PricingStatus[] = [
    'NOT_APPLICABLE',
    'PRICED',
    'FREE',
    'UNPRICED',
    'CALCULATION_FAILED',
]

export const TURN_STATES = [
    'NEW',
    'PROCESSING',
    'SUCCEEDED',
    'FAILED',
    'AMBIGUOUS',
] as const

export type ChatTurnState =
    typeof TURN_STATES[number]

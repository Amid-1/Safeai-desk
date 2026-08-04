export type UserRole =
    | 'SUPER_ADMIN'
    | 'ADMIN'
    | 'USER'

export type ChatMessageRole =
    | 'USER'
    | 'ASSISTANT'
    | 'SYSTEM'

export type ChatMessageStatus =
    | 'PENDING'
    | 'COMPLETED'
    | 'FAILED'

export type AiResponseStatus =
    | 'COMPLETED'
    | 'REFUSED'
    | 'INCOMPLETE'

export type UsageStatus =
    | 'NOT_APPLICABLE'
    | 'AVAILABLE'
    | 'MISSING'
    | 'PARTIAL'

export type PricingStatus =
    | 'NOT_APPLICABLE'
    | 'PRICED'
    | 'FREE'
    | 'UNPRICED'
    | 'CALCULATION_FAILED'

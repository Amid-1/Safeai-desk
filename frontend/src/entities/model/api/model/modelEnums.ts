// frontend/src/entities/model/api/model/modelEnums.ts
// Назначение: согласованные со стороны сервера перечисления и причины маршрутизации.

export const RUNTIME_MODEL_PROBE_STATUSES = [
    'AVAILABLE',
    'AUTH_ERROR',
    'RATE_LIMITED',
    'MODEL_NOT_FOUND',
    'UNAVAILABLE',
    'CONFIGURATION_MISMATCH',
    'ERROR',
] as const

export const RUNTIME_ROUTING_MODES = [
    'SINGLE_PROVIDER_STATIC',
] as const

export const RUNTIME_DATA_RETENTION_STATUSES = [
    'NOT_DECLARED',
    'STANDARD',
    'ZERO_DATA_RETENTION',
    'CUSTOM',
] as const

export const RUNTIME_HEALTH_STATUSES = [
    'NOT_PROBED',
    'AVAILABLE',
    'UNAVAILABLE',
] as const

export const RUNTIME_PRICING_STATUSES = [
    'UNPRICED',
    'FREE',
    'CONFIGURED',
] as const

export const INPUT_ACCOUNTING_VERSIONS = [
    'UTF8_STRUCTURAL_UNITS_V2',
] as const

export const MODEL_CAPABILITIES = [
    'TOOLS',
    'VISION',
    'STRUCTURED_OUTPUT',
] as const

export const MODEL_MODALITIES = [
    'TEXT',
    'IMAGE',
    'AUDIO',
] as const

export const MODEL_LIFECYCLES = [
    'ACTIVE',
    'DEPRECATED',
    'DISABLED',
    'RETIRED',
] as const

export const MODEL_RETENTION_STATUSES = [
    'NOT_DECLARED',
    'STANDARD',
    'ZERO_DATA_RETENTION',
    'CUSTOM',
] as const

export const MODEL_TRAINING_USE_STATUSES = [
    'NOT_DECLARED',
    'NOT_USED',
    'MAY_BE_USED',
    'CONTRACTUAL_NO_TRAINING',
] as const

export const MODEL_PRICING_STATUSES = [
    'UNPRICED',
    'FREE',
    'CONFIGURED',
    'INCOMPLETE',
] as const

export const MODEL_CATALOG_SOURCES = [
    'MANUAL',
    'RUNTIME_IMPORT',
    'MIGRATED',
] as const

export const BUDGET_ENFORCEMENTS = [
    'SOFT',
    'HARD',
] as const

export const MONTHLY_COST_STATES = [
    'NOT_EVALUATED',
    'KNOWN',
    'UNKNOWN',
] as const

export const MODEL_ROUTE_OUTCOMES = [
    'ALLOWED',
    'DENIED',
] as const

export const ALLOWED_REASONS = [
    'REQUESTED_MODEL',
    'POLICY_DEFAULT',
    'RUNTIME_ONLY_MATCH',
    'LEGACY_RUNTIME_FALLBACK',
] as const

export const DENIED_REASONS = [
    'MODEL_NOT_ALLOWED',
    'MODEL_DENIED',
    'MODEL_NOT_FOUND',
    'AMBIGUOUS_RUNTIME_MAPPING',
    'MODEL_DISABLED',
    'RUNTIME_MISMATCH',
    'CAPABILITY_UNSUPPORTED',
    'INPUT_LIMIT_EXCEEDED',
    'OUTPUT_LIMIT_EXCEEDED',
    'PRICING_INCOMPLETE',
    'TRAINING_POLICY_UNSATISFIED',
    'RETENTION_POLICY_UNSATISFIED',
    'REQUEST_COST_LIMIT_EXCEEDED',
    'MONTHLY_BUDGET_EXCEEDED',
    'MONTHLY_BUDGET_UNVERIFIABLE',
] as const

export const MODEL_ROUTE_REASONS = [
    ...ALLOWED_REASONS,
    ...DENIED_REASONS,
] as const

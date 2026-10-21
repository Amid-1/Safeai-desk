// ============================================================
// frontend/src/api/adminAudit/auditConstants.ts
// ============================================================



export const MAX_AUDIT_EVENT_TYPE_LENGTH = 128

export const MAX_AUDIT_EMAIL_LENGTH = 320

export const MAX_AUDIT_DISPLAY_NAME_LENGTH = 255

export const MAX_AUDIT_DETAILS_DEPTH = 8

export const MAX_AUDIT_DETAILS_NODES = 2_000

export const MAX_AUDIT_DETAILS_KEYS = 200

export const MAX_AUDIT_DETAILS_KEY_LENGTH = 256

export const MAX_AUDIT_DETAILS_ARRAY_ITEMS = 200

export const MAX_AUDIT_DETAILS_STRING_LENGTH = 4_096

export const MAX_AUDIT_DETAILS_TOTAL_CHARACTERS =
    64 * 1_024

export const SENSITIVE_AUDIT_KEYS = new Set([
    'authorization',
    'proxyauthorization',
    'cookie',
    'setcookie',
    'password',
    'passwordhash',
    'secret',
    'clientsecret',
    'apikey',
    'accesstoken',
    'refreshtoken',
    'idtoken',
    'privatekey',
    'credential',
    'credentials',
    'sessiontoken',
])

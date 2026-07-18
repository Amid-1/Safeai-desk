// ============================================================
// frontend/src/constants/auditEvents.ts
// ============================================================

export const AUDIT_EVENT_TYPES = [
    'USER_LOGIN_SUCCESS',
    'USER_LOGIN_FAILED',
    'USER_LOGOUT',

    'CHAT_CREATED',
    'CHAT_MESSAGE_SENT',
    'AI_RESPONSE_RECEIVED',
    'AI_RESPONSE_FAILED',

    'USER_CREATED',
    'USER_UPDATED',
    'USER_ENABLED_CHANGED',
    'USER_ROLES_CHANGED',
    'USER_PASSWORD_RESET',

    'ORGANIZATION_CREATED',
    'ORGANIZATION_NAME_CHANGED',
    'ORGANIZATION_ENABLED_CHANGED',

    'RATE_LIMIT_EXCEEDED',
    'SECURITY_REFRESH_REUSE_DETECTED',
] as const

export type AuditEventType =
    typeof AUDIT_EVENT_TYPES[number]

const AUDIT_EVENT_TYPE_LABELS: Record<AuditEventType, string> = {
    USER_LOGIN_SUCCESS: 'Успешный вход',
    USER_LOGIN_FAILED: 'Ошибка входа',
    USER_LOGOUT: 'Выход из системы',

    CHAT_CREATED: 'Чат создан',
    CHAT_MESSAGE_SENT: 'Сообщение отправлено',
    AI_RESPONSE_RECEIVED: 'Ответ AI получен',
    AI_RESPONSE_FAILED: 'Ошибка AI-ответа',

    USER_CREATED: 'Пользователь создан',
    USER_UPDATED: 'Пользователь изменён',
    USER_ENABLED_CHANGED: 'Статус пользователя изменён',
    USER_ROLES_CHANGED: 'Роли пользователя изменены',
    USER_PASSWORD_RESET: 'Пароль пользователя сброшен',

    ORGANIZATION_CREATED: 'Организация создана',
    ORGANIZATION_NAME_CHANGED:
        'Название организации изменено',
    ORGANIZATION_ENABLED_CHANGED:
        'Статус организации изменён',

    RATE_LIMIT_EXCEEDED: 'Превышен лимит',
    SECURITY_REFRESH_REUSE_DETECTED:
        'Повторное использование refresh token',
}

export function getAuditEventTypeLabel(
    eventType: string,
): string {
    return AUDIT_EVENT_TYPE_LABELS[
        eventType as AuditEventType
        ] ?? eventType
}
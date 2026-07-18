// ============================================================
// frontend/src/constants/auditEvents.ts
// ============================================================
export const AUDIT_EVENT_TYPES = [
    'USER_LOGIN_SUCCESS',
    'USER_LOGIN_FAILED',
    'CHAT_CREATED',
    'CHAT_MESSAGE_SENT',
    'AI_RESPONSE_RECEIVED',
    'AI_RESPONSE_FAILED',
    'USER_CREATED',
    'ORGANIZATION_CREATED',
    'USER_ENABLED_CHANGED',
    'USER_ROLES_CHANGED',
    'USER_PASSWORD_RESET',
    'USER_PERMANENTLY_DELETED',
    'RATE_LIMIT_EXCEEDED',
    'SECURITY_REFRESH_REUSE_DETECTED',
    'USER_LOGOUT',
    'ORGANIZATION_NAME_CHANGED',
    'ORGANIZATION_ENABLED_CHANGED',
    'USER_UPDATED',
] as const

export type AuditEventType =
    typeof AUDIT_EVENT_TYPES[number]

const AUDIT_EVENT_TYPE_LABELS:
    Record<AuditEventType, string> = {
    USER_LOGIN_SUCCESS:
        'Успешный вход пользователя',
    USER_LOGIN_FAILED:
        'Неудачная попытка входа',
    CHAT_CREATED:
        'Создан чат',
    CHAT_MESSAGE_SENT:
        'Отправлено сообщение',
    AI_RESPONSE_RECEIVED:
        'Получен ответ ИИ',
    AI_RESPONSE_FAILED:
        'Ошибка ответа ИИ',
    USER_CREATED:
        'Создан пользователь',
    ORGANIZATION_CREATED:
        'Создана организация',
    USER_ENABLED_CHANGED:
        'Изменён статус пользователя',
    USER_ROLES_CHANGED:
        'Изменены роли пользователя',
    USER_PASSWORD_RESET:
        'Установлен новый пароль',
    USER_PERMANENTLY_DELETED:
        'Пользователь удалён навсегда',
    RATE_LIMIT_EXCEEDED:
        'Превышено ограничение запросов',
    SECURITY_REFRESH_REUSE_DETECTED:
        'Обнаружено повторное использование refresh token',
    USER_LOGOUT:
        'Выход пользователя',
    ORGANIZATION_NAME_CHANGED:
        'Изменено название организации',
    ORGANIZATION_ENABLED_CHANGED:
        'Изменён статус организации',
    USER_UPDATED:
        'Изменён профиль пользователя',
}

export function getAuditEventTypeLabel(
    eventType: string,
): string {
    if (
        Object.prototype.hasOwnProperty.call(
            AUDIT_EVENT_TYPE_LABELS,
            eventType,
        )
    ) {
        return AUDIT_EVENT_TYPE_LABELS[
            eventType as AuditEventType
            ]
    }

    return eventType
}

export const AUDIT_EVENT_TYPES = [
    'USER_LOGIN_SUCCESS',
    'USER_LOGIN_FAILED',
    'CHAT_CREATED',
    'CHAT_ARCHIVED',
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
    'KNOWLEDGE_BASE_CREATED',
    'KNOWLEDGE_BASE_UPDATED',
    'KNOWLEDGE_BASE_MEMBER_ADDED',
    'KNOWLEDGE_BASE_MEMBER_UPDATED',
    'KNOWLEDGE_BASE_MEMBER_REMOVED',
    'KNOWLEDGE_DOCUMENT_CREATED',
    'KNOWLEDGE_DOCUMENT_VERSION_UPLOADED',
    'KNOWLEDGE_DOCUMENT_DOWNLOADED',
    'KNOWLEDGE_INGESTION_READY',
    'KNOWLEDGE_INGESTION_FAILED',
    'KNOWLEDGE_RETRIEVAL_COMPLETED',
    'KNOWLEDGE_ANSWER_GENERATED',
    'KNOWLEDGE_REINDEX_REQUESTED',
    'MODEL_CATALOG_VERSION_CREATED',
    'MODEL_POLICY_VERSION_CREATED',
    'MODEL_ROUTE_DECIDED',
    'MODEL_ROUTE_DENIED',
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

    CHAT_ARCHIVED:
        'Чат убран из списка',

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

    KNOWLEDGE_BASE_CREATED:
        'Создана база знаний',

    KNOWLEDGE_BASE_UPDATED:
        'Изменена база знаний',

    KNOWLEDGE_BASE_MEMBER_ADDED:
        'Добавлен участник базы знаний',

    KNOWLEDGE_BASE_MEMBER_UPDATED:
        'Изменён доступ участника базы знаний',

    KNOWLEDGE_BASE_MEMBER_REMOVED:
        'Удалён участник базы знаний',

    KNOWLEDGE_DOCUMENT_CREATED:
        'Загружен документ',

    KNOWLEDGE_DOCUMENT_VERSION_UPLOADED:
        'Загружена версия документа',

    KNOWLEDGE_DOCUMENT_DOWNLOADED:
        'Скачан документ',

    KNOWLEDGE_INGESTION_READY:
        'Документ готов для поиска',

    KNOWLEDGE_INGESTION_FAILED:
        'Ошибка обработки документа',

    KNOWLEDGE_RETRIEVAL_COMPLETED:
        'Выполнен поиск по знаниям',

    KNOWLEDGE_ANSWER_GENERATED:
        'Сформирован ответ по знаниям',

    KNOWLEDGE_REINDEX_REQUESTED:
        'Запущена повторная индексация',

    MODEL_CATALOG_VERSION_CREATED:
        'Создана версия каталога модели',

    MODEL_POLICY_VERSION_CREATED:
        'Создана версия model policy',

    MODEL_ROUTE_DECIDED:
        'Маршрут модели разрешён',

    MODEL_ROUTE_DENIED:
        'Маршрут модели отклонён',
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

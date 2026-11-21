// frontend/src/features/audit/constants/auditEvents.ts

export const AUDIT_EVENT_TYPES = [
    'USER_LOGIN_SUCCESS',
    'USER_LOGIN_FAILED',

    'CHAT_CREATED',
    'CHAT_RENAMED',
    'CHAT_ARCHIVED',
    'CHAT_RESTORED',
    'CHAT_CONTENT_ERASURE_REQUESTED',
    'CHAT_CONTENT_ERASURE_COMPLETED',
    'CHAT_CONTENT_ERASURE_REVIEW_REQUIRED',
    'CHAT_CONTENT_ERASURE_RETENTION_BLOCKED',
    'CHAT_CONTENT_ERASURE_RESUMED',
    'CHAT_CONTENT_ERASURE_CANCELLED',
    'CHAT_RETENTION_HOLD_RELEASED',
    'CHAT_ERASURE_OPERATOR_APPROVED',
    'CHAT_MESSAGE_SENT',
    'AI_RESPONSE_RECEIVED',
    'AI_RESPONSE_FAILED',

    'USER_CREATED',
    'USER_UPDATED',
    'USER_ENABLED_CHANGED',
    'USER_ROLES_CHANGED',
    'USER_PASSWORD_RESET',
    'USER_PERMANENTLY_DELETED',

    'ORGANIZATION_CREATED',
    'ORGANIZATION_NAME_CHANGED',
    'ORGANIZATION_ENABLED_CHANGED',

    'RATE_LIMIT_EXCEEDED',
    'SECURITY_REFRESH_REUSE_DETECTED',
    'USER_LOGOUT',

    'KNOWLEDGE_BASE_CREATED',
    'KNOWLEDGE_BASE_UPDATED',
    'KNOWLEDGE_BASE_MEMBER_ADDED',
    'KNOWLEDGE_BASE_MEMBER_UPDATED',
    'KNOWLEDGE_BASE_MEMBER_REMOVED',
    'KNOWLEDGE_BASE_DELETION_REQUESTED',
    'KNOWLEDGE_BASE_DELETION_RESUMED',
    'KNOWLEDGE_BASE_DELETION_COMPLETED',
    'KNOWLEDGE_BASE_DELETION_REVIEW_REQUIRED',
    'KNOWLEDGE_BASE_DELETION_RETENTION_BLOCKED',

    'KNOWLEDGE_DOCUMENT_CREATED',
    'KNOWLEDGE_DOCUMENT_UPDATED',
    'KNOWLEDGE_DOCUMENT_VERSION_UPLOADED',
    'KNOWLEDGE_DOCUMENT_DOWNLOADED',
    'KNOWLEDGE_DOCUMENT_DELETION_REQUESTED',
    'KNOWLEDGE_DOCUMENT_DELETION_RESUMED',
    'KNOWLEDGE_DOCUMENT_DELETION_COMPLETED',
    'KNOWLEDGE_DOCUMENT_DELETION_REVIEW_REQUIRED',
    'KNOWLEDGE_DOCUMENT_DELETION_RETENTION_BLOCKED',

    'KNOWLEDGE_REINDEX_REQUESTED',
    'KNOWLEDGE_INGESTION_READY',
    'KNOWLEDGE_INGESTION_FAILED',
    'KNOWLEDGE_RETRIEVAL_COMPLETED',
    'KNOWLEDGE_EVALUATION_COMPLETED',
    'KNOWLEDGE_ANSWER_GENERATED',

    'MODEL_CATALOG_VERSION_CREATED',
    'MODEL_POLICY_VERSION_CREATED',
    'MODEL_ROUTE_DECIDED',
    'MODEL_ROUTE_DENIED',
    'AUDIT_OUTBOX_DEAD_LETTER_RECOVERED',
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

    CHAT_RENAMED:
        'Переименован чат',

    CHAT_ARCHIVED:
        'Чат убран из списка',

    CHAT_RESTORED: 'Чат восстановлен из архива',
    CHAT_CONTENT_ERASURE_REQUESTED: 'Запрошено удаление содержимого чата',
    CHAT_CONTENT_ERASURE_COMPLETED: 'Удаление содержимого чата завершено',
    CHAT_CONTENT_ERASURE_REVIEW_REQUIRED: 'Удаление чата требует проверки',
    CHAT_CONTENT_ERASURE_RETENTION_BLOCKED: 'Удаление чата ограничено сроком хранения',
    CHAT_CONTENT_ERASURE_RESUMED: 'Удаление содержимого чата возобновлено',
    CHAT_CONTENT_ERASURE_CANCELLED: 'Удаление содержимого чата отменено',
    CHAT_RETENTION_HOLD_RELEASED: 'Снято ограничение хранения чата',
    CHAT_ERASURE_OPERATOR_APPROVED: 'Оператор подтвердил удаление чата',

    CHAT_MESSAGE_SENT:
        'Отправлено сообщение',

    AI_RESPONSE_RECEIVED:
        'Получен ответ ИИ',

    AI_RESPONSE_FAILED:
        'Ошибка ответа ИИ',

    USER_CREATED:
        'Создан пользователь',

    USER_UPDATED:
        'Изменён профиль пользователя',

    USER_ENABLED_CHANGED:
        'Изменён статус пользователя',

    USER_ROLES_CHANGED:
        'Изменены роли пользователя',

    USER_PASSWORD_RESET:
        'Установлен новый пароль',

    USER_PERMANENTLY_DELETED:
        'Пользователь удалён навсегда',

    ORGANIZATION_CREATED:
        'Создана организация',

    ORGANIZATION_NAME_CHANGED:
        'Изменено название организации',

    ORGANIZATION_ENABLED_CHANGED:
        'Изменён статус организации',

    RATE_LIMIT_EXCEEDED:
        'Превышено ограничение запросов',

    SECURITY_REFRESH_REUSE_DETECTED:
        'Обнаружено повторное использование refresh token',

    USER_LOGOUT:
        'Выход пользователя',

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

    KNOWLEDGE_BASE_DELETION_REQUESTED: 'Запрошено удаление базы знаний',
    KNOWLEDGE_BASE_DELETION_RESUMED: 'Удаление базы знаний возобновлено',
    KNOWLEDGE_BASE_DELETION_COMPLETED: 'Удаление базы знаний завершено',
    KNOWLEDGE_BASE_DELETION_REVIEW_REQUIRED: 'Удаление базы знаний требует проверки',
    KNOWLEDGE_BASE_DELETION_RETENTION_BLOCKED: 'Удаление базы знаний ограничено сроком хранения',

    KNOWLEDGE_DOCUMENT_CREATED:
        'Загружен документ',

    KNOWLEDGE_DOCUMENT_UPDATED:
        'Изменён документ',

    KNOWLEDGE_DOCUMENT_VERSION_UPLOADED:
        'Загружена версия документа',

    KNOWLEDGE_DOCUMENT_DOWNLOADED:
        'Скачан документ',

    KNOWLEDGE_DOCUMENT_DELETION_REQUESTED: 'Запрошено удаление документа',
    KNOWLEDGE_DOCUMENT_DELETION_RESUMED: 'Удаление документа возобновлено',
    KNOWLEDGE_DOCUMENT_DELETION_COMPLETED: 'Удаление документа завершено',
    KNOWLEDGE_DOCUMENT_DELETION_REVIEW_REQUIRED: 'Удаление документа требует проверки',
    KNOWLEDGE_DOCUMENT_DELETION_RETENTION_BLOCKED: 'Удаление документа ограничено сроком хранения',

    KNOWLEDGE_REINDEX_REQUESTED:
        'Запущена повторная индексация',

    KNOWLEDGE_INGESTION_READY:
        'Документ готов для поиска',

    KNOWLEDGE_INGESTION_FAILED:
        'Ошибка обработки документа',

    KNOWLEDGE_RETRIEVAL_COMPLETED:
        'Выполнен поиск по знаниям',

    KNOWLEDGE_EVALUATION_COMPLETED:
        'Завершена оценка поиска по знаниям',

    KNOWLEDGE_ANSWER_GENERATED:
        'Сформирован ответ по знаниям',

    MODEL_CATALOG_VERSION_CREATED:
        'Создана версия каталога модели',

    MODEL_POLICY_VERSION_CREATED:
        'Создана версия правил использования AI',

    MODEL_ROUTE_DECIDED:
        'Маршрут модели разрешён',

    MODEL_ROUTE_DENIED:
        'Маршрут модели отклонён',
    AUDIT_OUTBOX_DEAD_LETTER_RECOVERED:
        'Восстановлено событие аудита из очереди ошибок',
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

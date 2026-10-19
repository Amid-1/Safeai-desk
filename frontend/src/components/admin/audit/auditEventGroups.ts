import { getAuditEventTypeLabel } from '../../../constants/auditEvents'

/** Visual categories only; the exact server-provided eventType is preserved. */
export const AUDIT_EVENT_GROUPS = [
    'Авторизация и безопасность',
    'Пользователи и организации',
    'Базы знаний и документы',
    'AI, чаты и модели',
    'Системные события',
] as const

export type AuditEventGroup = typeof AUDIT_EVENT_GROUPS[number]

export function getAuditEventGroup(type: string): AuditEventGroup {
    if (type.startsWith('AUDIT_') || type.startsWith('OUTBOX_')) {
        return 'Системные события'
    }
    if (type.startsWith('KNOWLEDGE_')) {
        return 'Базы знаний и документы'
    }
    if (type.startsWith('AI_') || type.startsWith('CHAT_')
        || type.startsWith('MODEL_')) {
        return 'AI, чаты и модели'
    }
    if (type.startsWith('USER_LOGIN_') || type === 'USER_LOGOUT'
        || type.startsWith('SECURITY_') || type.startsWith('RATE_LIMIT_')
        || type.includes('AUTH') || type.includes('TOKEN_')) {
        return 'Авторизация и безопасность'
    }
    if (type.startsWith('USER_') || type.startsWith('ORGANIZATION_')) {
        return 'Пользователи и организации'
    }
    return 'Системные события'
}

export function groupAuditEventTypes(
    types: readonly string[], search: string, selected: string,
): Array<{ group: AuditEventGroup; types: string[] }> {
    const needle = search.trim().toLocaleLowerCase('ru')
    const unique = new Set(types)
    if (selected) unique.add(selected)
    return AUDIT_EVENT_GROUPS.map((group) => ({
        group,
        types: [...unique]
            .filter((type) => getAuditEventGroup(type) === group
                && (type === selected || !needle
                    || type.toLocaleLowerCase('ru').includes(needle)
                    || getAuditEventTypeLabel(type)
                        .toLocaleLowerCase('ru').includes(needle)))
            .sort((a, b) => getAuditEventTypeLabel(a).localeCompare(
                getAuditEventTypeLabel(b), 'ru', { sensitivity: 'base' },
            )),
    })).filter(({ types: items }) => items.length > 0)
}

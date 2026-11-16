// frontend/src/features/audit/ui/auditEventPresentation.ts
import type { AuditEvent } from '../api/adminAuditApi'
import { getAuditEventTypeLabel } from '../constants/auditEvents'
import { describeModelRouteDenial, MODEL_DECISION_ID_PATTERN } from '../../../shared/lib/modelRouteDenial'

export function auditEventTitle(type: string): string {
    if (type === 'MODEL_ROUTE_DENIED') return 'AI-запрос заблокирован'
    if (type === 'MODEL_ROUTE_DECIDED') return 'AI-запрос разрешён'
    if (type === 'MODEL_POLICY_VERSION_CREATED') return 'Изменены правила использования AI'
    return getAuditEventTypeLabel(type)
}

export function auditEventResult(type: string): { label: string; tone: string } {
    if (type === 'MODEL_ROUTE_DENIED') return { label: 'Отклонён', tone: 'danger' }
    if (type === 'MODEL_ROUTE_DECIDED') return { label: 'Разрешён', tone: 'success' }
    if (type === 'USER_LOGIN_FAILED') return { label: 'Вход не выполнен', tone: 'danger' }
    if (type.includes('DENIED')) return { label: 'Отклонён', tone: 'danger' }
    if (type.includes('FAILED') || type.includes('DEAD_LETTER')) return { label: 'Ошибка', tone: 'danger' }
    if (type.startsWith('SECURITY_') || type.includes('EXCEEDED')) return { label: 'Предупреждение', tone: 'warning' }
    if (type.includes('DELETED')) return { label: 'Удалено', tone: 'danger' }
    if (type.endsWith('_REQUESTED')) return { label: 'Запрошено', tone: 'neutral' }
    if (['USER_LOGIN_SUCCESS', 'KNOWLEDGE_INGESTION_READY', 'AI_RESPONSE_RECEIVED',
        'KNOWLEDGE_DOCUMENT_CREATED', 'KNOWLEDGE_DOCUMENT_VERSION_UPLOADED', 'CHAT_CREATED'].includes(type)) {
        return { label: 'Успешно', tone: 'success' }
    }
    return { label: 'Зафиксировано', tone: 'neutral' }
}

const friendlyFields: Readonly<Record<string, string>> = {
    modelKey: 'Модель', provider: 'Провайдер', providerModelId: 'Модель провайдера',
    version: 'Версия', documentName: 'Документ', fileName: 'Имя файла',
    knowledgeBaseName: 'База знаний', title: 'Название', name: 'Название', enabled: 'Включено',
    email: 'Email', targetUserEmail: 'Пользователь, которого касается изменение',
    role: 'Роль', roles: 'Роли', oldRoles: 'Предыдущие роли', newRoles: 'Новые роли',
    sessionsRevoked: 'Активные сеансы отозваны', versionNumber: 'Номер версии документа',
    defaultModelKey: 'Модель по умолчанию', allowModelKeyCount: 'Разрешённых моделей',
    denyModelKeyCount: 'Запрещённых моделей', maxInputTokens: 'Лимит входных токенов',
    maxOutputTokens: 'Лимит выходных токенов', maxRequestCostUsd: 'Лимит стоимости запроса, USD',
    monthlyBudgetUsd: 'Месячный бюджет, USD', requireNoTraining: 'Запрет обучения на данных',
    requireZeroDataRetention: 'Требование отсутствия хранения данных',
    oldName: 'Прежнее название', newName: 'Новое название', chunkCount: 'Число фрагментов',
}

const eventDescriptions: Readonly<Record<string, string>> = {
    USER_LOGIN_SUCCESS: 'Пользователь успешно вошёл в систему.',
    USER_LOGIN_FAILED: 'Попытка входа в систему завершилась отказом.',
    USER_LOGOUT: 'Пользователь завершил сеанс работы.',
    USER_ROLES_CHANGED: 'Изменены права пользователя. Ниже указаны сохранённые сведения об изменении.',
    USER_PASSWORD_RESET: 'Зафиксирована установка нового пароля пользователя. Сам пароль в журнале не отображается.',
    KNOWLEDGE_DOCUMENT_CREATED: 'В базу знаний добавлен документ. Это ещё не подтверждает его готовность к поиску.',
    KNOWLEDGE_DOCUMENT_VERSION_UPLOADED: 'Загружена версия документа. Обработка выполняется отдельно.',
    KNOWLEDGE_INGESTION_READY: 'Обработка документа завершена; опубликованная версия готова к поиску.',
    KNOWLEDGE_INGESTION_FAILED: 'Не удалось завершить обработку документа. Проверьте его состояние в базе знаний.',
    MODEL_POLICY_VERSION_CREATED: 'Сохранена новая версия правил использования AI для организации.',
    CHAT_CREATED: 'Создан новый чат.',
    AI_RESPONSE_RECEIVED: 'Зафиксировано получение ответа AI.',
}

// Translate only the documented backend sanitizer markers; keep the original
// event.details untouched for the diagnostic report and clipboard.
const omittedValues: Readonly<Record<string, string>> = {
    '[OMITTED_UNAPPROVED_TEXT]': 'Не сохранено по правилам безопасности',
    '[REDACTED]': 'Скрыто в целях безопасности',
    '[MAX_DEPTH]': 'Данные ограничены по глубине',
    '[BUDGET_EXCEEDED]': 'Данные ограничены по объёму',
    '[NON_FINITE_NUMBER]': 'Некорректное числовое значение',
    '[NUMBER_TOO_LARGE]': 'Число превышает допустимый размер',
}

function omittedValueDescription(value: string): string | undefined {
    if (Object.hasOwn(omittedValues, value)) return omittedValues[value]
    if (/^\[UNSUPPORTED_TYPE:[^\]]+\]$/.test(value)) return 'Неподдерживаемый формат данных'
    return undefined
}

function friendlyText(value: string): string {
    return omittedValueDescription(value) ?? value
}

function loginEmailFields(event: AuditEvent): { label: string; value: string }[] {
    const email = event.detailsInvalid ? undefined : event.details.email
    const readable = typeof email === 'string' && email.trim() && email !== 'unknown'
        ? email : undefined
    if (event.eventType === 'USER_LOGIN_FAILED') {
        return [{ label: 'Email при попытке входа', value: readable
            ? friendlyText(readable) : 'Адрес не сохранён или недоступен' }]
    }
    // Successful login/logout already has a server-owned actor snapshot. A
    // legacy record may only have a readable address in the event details.
    if (event.actorEmail?.trim() || !readable || omittedValueDescription(readable)) return []
    return [{ label: 'Email на момент события', value: readable }]
}

export function auditEventPresentation(event: AuditEvent) {
    const details = event.details
    const isRoute = event.eventType === 'MODEL_ROUTE_DENIED' || event.eventType === 'MODEL_ROUTE_DECIDED'
    const decisionId = isRoute && !event.detailsInvalid && typeof details.decisionId === 'string'
        && MODEL_DECISION_ID_PATTERN.test(details.decisionId) ? details.decisionId : null
    const isAuth = ['USER_LOGIN_SUCCESS', 'USER_LOGIN_FAILED', 'USER_LOGOUT'].includes(event.eventType)
    const fields = Object.entries(friendlyFields).flatMap(([key, label]) => {
        if (event.detailsInvalid) return []
        if (isAuth && key === 'email') return []
        const value = details[key]
        if (typeof value === 'string') return [{ label, value: key === 'provider' && value === 'mock'
            ? 'Тестовый провайдер (mock)' : friendlyText(value) }]
        if (typeof value === 'number') return [{ label, value: String(value) }]
        if (typeof value === 'boolean') return [{ label, value: value ? 'Да' : 'Нет' }]
        if (Array.isArray(value) && value.every((item) => typeof item === 'string')) {
            return [{ label, value: value.map(friendlyText).join(', ') }]
        }
        return []
    })
    if (isAuth) fields.push(...loginEmailFields(event))
    if (isRoute && details.monthlyCommitmentState === 'NOT_EVALUATED') {
        fields.push({ label: 'Проверка месячного бюджета', value: 'Не выполнялась' })
    }
    if (event.eventType === 'AI_RESPONSE_RECEIVED' && !event.detailsInvalid) {
        const model = details.resolvedModel ?? details.model
        if (!fields.some(field => field.label === 'Модель') && typeof model === 'string' && model.trim()) {
            fields.push({ label: 'Модель', value: friendlyText(model) })
        }
        const usage = details.usageStatus
        if (usage === 'AVAILABLE' || usage === 'PARTIAL' || usage === undefined) {
            for (const [key, label] of [['inputTokens', 'Входные токены'], ['outputTokens', 'Выходные токены']] as const) {
                const count = details[key]
                if (typeof count === 'number' && Number.isSafeInteger(count) && count >= 0) {
                    fields.push({ label, value: count.toLocaleString('ru-RU') })
                }
            }
        }
        if (usage === 'PARTIAL' || usage === 'MISSING') {
            fields.push({ label: 'Учёт токенов', value: usage === 'PARTIAL' ? 'Частичные данные' : 'Нет данных' })
        }
        const cost = details.costUsd
        const pricing = details.pricingStatus
        if (typeof cost === 'number' && Number.isFinite(cost) && cost >= 0
            && (pricing === 'PRICED' || pricing === 'FREE' || pricing === undefined)) {
            fields.push({ label: 'Стоимость запроса', value: `${cost} USD` })
        } else if (pricing === 'UNPRICED' || pricing === 'CALCULATION_FAILED') {
            fields.push({ label: 'Стоимость запроса', value: 'Недоступна' })
        }
    }
    return {
        title: auditEventTitle(event.eventType), result: auditEventResult(event.eventType), decisionId, fields,
        description: event.eventType === 'MODEL_ROUTE_DENIED'
            ? describeModelRouteDenial(details.reason)
            : event.eventType === 'MODEL_ROUTE_DECIDED'
                ? 'Сервер разрешил использование модели. Это не подтверждает получение ответа от AI.'
                : eventDescriptions[event.eventType] ?? 'Действие зафиксировано в журнале безопасности.',
    }
}

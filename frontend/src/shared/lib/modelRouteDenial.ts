// frontend/src/shared/lib/modelRouteDenial.ts
const reasons: Readonly<Record<string, string>> = {
    MODEL_NOT_ALLOWED: 'Модель не разрешена для вашей организации или правила ещё не настроены.',
    MODEL_DENIED: 'Модель запрещена правилами вашей организации.',
    MODEL_NOT_FOUND: 'Не найдена подходящая модель в каталоге.',
    AMBIGUOUS_RUNTIME_MAPPING: 'Несколько записей каталога соответствуют подключению. Требуется проверка настроек.',
    MODEL_DISABLED: 'Модель отключена или больше не используется.',
    RUNTIME_MISMATCH: 'Запрошенная модель не соответствует подключённой на сервере.',
    CAPABILITY_UNSUPPORTED: 'Модель не поддерживает необходимые возможности запроса.',
    INPUT_LIMIT_EXCEEDED: 'Превышен лимит объёма входных данных.',
    OUTPUT_LIMIT_EXCEEDED: 'Превышен лимит объёма ответа.',
    PRICING_INCOMPLETE: 'Недостаточно данных для расчёта стоимости запроса.',
    TRAINING_POLICY_UNSATISFIED: 'Условия использования данных для обучения не соответствуют правилам организации.',
    RETENTION_POLICY_UNSATISFIED: 'Условия хранения данных не соответствуют правилам организации.',
    REQUEST_COST_LIMIT_EXCEEDED: 'Превышен допустимый расход на один запрос.',
    MONTHLY_BUDGET_EXCEEDED: 'Исчерпан месячный бюджет AI.',
    MONTHLY_BUDGET_UNVERIFIABLE: 'Невозможно подтвердить соблюдение месячного бюджета AI.',
}

export function describeModelRouteDenial(reason: unknown): string {
    return typeof reason === 'string' && Object.hasOwn(reasons, reason)
        ? reasons[reason] : 'Запрос отклонён правилами использования AI. Проверьте диагностику решения.'
}

export const MODEL_DECISION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Recognise only the existing server-owned public denial contract; never display arbitrary error text. */
export function parsePublicModelRouteDenial(status: number, code: string | undefined, message: string) {
    if (status !== 403 || code !== 'FORBIDDEN') return null
    const match = /^AI model route отклонён политикой SafeAI: ([A-Z_]+)\. decisionId=([0-9a-f-]{36})$/i.exec(message)
    if (!match || !Object.hasOwn(reasons, match[1]) || !MODEL_DECISION_ID_PATTERN.test(match[2])) return null
    return { reason: match[1], decisionId: match[2], message: describeModelRouteDenial(match[1]) }
}

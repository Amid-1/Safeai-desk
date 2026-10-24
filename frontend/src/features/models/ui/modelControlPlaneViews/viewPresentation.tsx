// frontend/src/features/models/ui/modelControlPlaneViews/viewPresentation.tsx
// Назначение: подписи, статусы и форматирование данных моделей.

import type {
    ModelCatalogEntry,
    ModelLifecycle,
    ModelPricingStatus,
    ModelRetentionStatus,
    ModelRouteDecision,
    ModelTrainingUseStatus,
    OrganizationModelPolicy,
    RuntimeModelProbe,
    RuntimeModelProbeStatus,
    RuntimeModelStatus,
} from '../../../../entities/model/api/modelApi'

export type StatusTone =
    | 'success'
    | 'warning'
    | 'danger'
    | 'neutral'

export function StatusPill({
    tone,
    label,
}: {
    tone: StatusTone
    label: string
}) {
    return (
        <span
            className={`models-status models-status--${tone}`}
        >
            {label}
        </span>
    )
}

export function InfoHint({
    text,
}: {
    text: string
}) {
    return (
        <span
            className="models-help"
            tabIndex={0}
            aria-label={text}
            data-tip={text}
        >
            ?
        </span>
    )
}

export function enumLabel(
    value: string | null | undefined,
): string {
    if (!value) {
        return '—'
    }

    return value
        .toLowerCase()
        .split('_')
        .map((part) =>
            part
                ? `${part[0]?.toUpperCase() ?? ''}${part.slice(1)}`
                : part,
        )
        .join(' ')
}

export function runtimeModeLabel(
    routingMode: string,
): string {
    switch (routingMode) {
        case 'SINGLE_PROVIDER_STATIC':
            return 'Один фиксированный провайдер и модель'
        default:
            return enumLabel(routingMode)
    }
}

export function lifecycleLabel(
    lifecycle: ModelLifecycle,
): string {
    switch (lifecycle) {
        case 'ACTIVE':
            return 'Активна'
        case 'DEPRECATED':
            return 'Устаревает'
        case 'DISABLED':
            return 'Отключена'
        case 'RETIRED':
            return 'Выведена'
    }
}

export function lifecycleTone(
    lifecycle: ModelLifecycle,
): StatusTone {
    switch (lifecycle) {
        case 'ACTIVE':
            return 'success'
        case 'DEPRECATED':
            return 'warning'
        case 'DISABLED':
            return 'danger'
        case 'RETIRED':
            return 'neutral'
    }
}

export function retentionLabel(
    status: ModelRetentionStatus | string,
): string {
    switch (status) {
        case 'NOT_DECLARED':
            return 'Не заявлено'
        case 'STANDARD':
            return 'Стандартное хранение'
        case 'ZERO_DATA_RETENTION':
            return 'Без хранения после запроса'
        case 'CUSTOM':
            return 'Особые условия'
        default:
            return enumLabel(status)
    }
}

export function trainingUseLabel(
    status: ModelTrainingUseStatus,
): string {
    switch (status) {
        case 'NOT_DECLARED':
            return 'Не заявлено'
        case 'NOT_USED':
            return 'Не используется для обучения'
        case 'MAY_BE_USED':
            return 'Может использоваться для обучения'
        case 'CONTRACTUAL_NO_TRAINING':
            return 'Обучение запрещено договором'
    }
}

export function pricingStatusLabel(
    status: ModelPricingStatus | string,
): string {
    switch (status) {
        case 'UNPRICED':
            return 'Стоимость не указана'
        case 'FREE':
            return 'Бесплатно'
        case 'CONFIGURED':
            return 'Стоимость настроена'
        case 'INCOMPLETE':
            return 'Данные о стоимости неполные'
        default:
            return enumLabel(status)
    }
}

export function catalogSourceLabel(
    source: ModelCatalogEntry['source'],
): string {
    switch (source) {
        case 'RUNTIME_IMPORT':
            return 'Добавлена из подключённой модели'
        case 'MANUAL':
            return 'Создана вручную'
        case 'MIGRATED':
            return 'Перенесена миграцией'
    }
}

export function probeLabel(
    status: RuntimeModelProbeStatus,
): string {
    switch (status) {
        case 'AVAILABLE':
            return 'Доступна'
        case 'AUTH_ERROR':
            return 'Ошибка авторизации'
        case 'RATE_LIMITED':
            return 'Ограничена провайдером'
        case 'MODEL_NOT_FOUND':
            return 'Модель не найдена'
        case 'UNAVAILABLE':
            return 'Недоступна'
        case 'CONFIGURATION_MISMATCH':
            return 'Несовпадение конфигурации'
        case 'ERROR':
            return 'Ошибка проверки'
    }
}

export function probeTone(
    status: RuntimeModelProbeStatus,
): StatusTone {
    switch (status) {
        case 'AVAILABLE':
            return 'success'
        case 'RATE_LIMITED':
            return 'warning'
        case 'AUTH_ERROR':
        case 'MODEL_NOT_FOUND':
        case 'UNAVAILABLE':
        case 'CONFIGURATION_MISMATCH':
        case 'ERROR':
            return 'danger'
    }
}

export function runtimeHealthLabel(
    runtime: RuntimeModelStatus,
    probe: RuntimeModelProbe | null,
): string {
    if (probe) {
        return probeLabel(probe.status)
    }

    switch (runtime.healthStatus) {
        case 'NOT_PROBED':
            return 'Не выполнялась'
        case 'AVAILABLE':
            return 'Доступна'
        case 'UNAVAILABLE':
            return 'Недоступна'
        default:
            return enumLabel(runtime.healthStatus)
    }
}

/**
 * Карточка Runtime показывает только возможности, которые сервер считает
 * доступными для фактического исполнения запроса.
 *
 * Наличие возможностей в каталоге не является доказательством поддержки
 * Runtime: отображаются именно серверные флаги TOOLS/VISION/STRUCTURED_OUTPUT.
 */
export function runtimeCapabilities(
    runtime: RuntimeModelStatus,
): string {
    const result = ['Текст']

    if (runtime.toolsSupported) {
        result.push('Инструменты')
    }
    if (runtime.visionSupported) {
        result.push('Изображения')
    }
    if (runtime.structuredOutputSupported) {
        result.push('Структурированный ответ')
    }

    return result.join(' · ')
}

export function policyAccessSummary(
    policy: OrganizationModelPolicy,
): string {
    const allowed =
        policy.allowModelKeys.length === 0
            ? 'все допустимые'
            : policy.allowModelKeys.length.toLocaleString('ru-RU')

    return (
        `Разрешённые: ${allowed}`
        + ` · Запрещённые: ${policy.denyModelKeys.length.toLocaleString('ru-RU')}`
    )
}

export function policyDataRequirements(
    policy: OrganizationModelPolicy,
): string {
    const requirements = [
        policy.requireCompletePricing
            ? 'Полные данные о стоимости'
            : null,
        policy.requireNoTraining
            ? 'Без использования для обучения'
            : null,
        policy.requireZeroDataRetention
            ? 'Без хранения после запроса'
            : null,
    ].filter(
        (value): value is string =>
            value !== null,
    )

    return requirements.length === 0
        ? 'Без дополнительных требований'
        : requirements.join(' · ')
}

export function policyTokenLimits(
    policy: OrganizationModelPolicy,
): string {
    if (
        policy.maxInputTokens === null
        && policy.maxOutputTokens === null
    ) {
        return 'Не ограничены правилами'
    }

    const input =
        policy.maxInputTokens === null
            ? 'без доп. лимита'
            : policy.maxInputTokens.toLocaleString('ru-RU')

    const output =
        policy.maxOutputTokens === null
            ? 'без доп. лимита'
            : policy.maxOutputTokens.toLocaleString('ru-RU')

    return `${input} / ${output}`
}

export function catalogCapabilities(
    entry: ModelCatalogEntry,
): string {
    const extra = entry.capabilities.map(
        (capability) => {
            switch (capability) {
                case 'TOOLS':
                    return 'Инструменты'
                case 'VISION':
                    return 'Изображения'
                case 'STRUCTURED_OUTPUT':
                    return 'Структурированный ответ'
            }
        },
    )

    return extra.length === 0
        ? 'Текст'
        : `Текст · ${extra.join(' · ')}`
}


export function routeReasonLabel(
    reason: ModelRouteDecision['reason'],
): string {
    const labels: Record<string, string> = {
        REQUESTED_MODEL: 'Использована явно запрошенная модель',
        POLICY_DEFAULT: 'Выбрана модель по умолчанию',
        RUNTIME_ONLY_MATCH: 'Совпала с подключённой моделью',
        LEGACY_RUNTIME_FALLBACK: 'Использован режим совместимости Runtime',
        MODEL_NOT_ALLOWED: 'Модель не входит в список разрешённых',
        MODEL_DENIED: 'Модель запрещена правилами',
        MODEL_NOT_FOUND: 'Модель не найдена',
        AMBIGUOUS_RUNTIME_MAPPING: 'Runtime неоднозначно сопоставлен с каталогом',
        MODEL_DISABLED: 'Модель отключена',
        RUNTIME_MISMATCH: 'Модель не совпадает с подключённой',
        CAPABILITY_UNSUPPORTED: 'Нужная возможность не поддерживается',
        INPUT_LIMIT_EXCEEDED: 'Превышен лимит входных токенов',
        OUTPUT_LIMIT_EXCEEDED: 'Превышен лимит выходных токенов',
        PRICING_INCOMPLETE: 'Недостаточно данных о стоимости',
        TRAINING_POLICY_UNSATISFIED: 'Не выполнено требование по обучению',
        RETENTION_POLICY_UNSATISFIED: 'Не выполнено требование по хранению данных',
        REQUEST_COST_LIMIT_EXCEEDED: 'Превышен лимит стоимости запроса',
        MONTHLY_BUDGET_EXCEEDED: 'Превышен месячный бюджет',
        MONTHLY_BUDGET_UNVERIFIABLE: 'Невозможно надёжно проверить месячный бюджет',
    }

    return labels[reason] ?? enumLabel(reason)
}


export function monthlyCostStateLabel(
    state: ModelRouteDecision['monthlyCostState'],
): string {
    switch (state) {
        case 'NOT_EVALUATED':
            return 'Не оценивалась'
        case 'KNOWN':
            return 'Известна'
        case 'UNKNOWN':
            return 'Неизвестна'
    }
}

export function budgetEnforcementLabel(
    enforcement: ModelRouteDecision['budgetEnforcement'],
): string {
    if (enforcement === null) {
        return 'Не применялся'
    }

    return enforcement === 'HARD'
        ? 'Жёсткий контроль'
        : 'Мягкий контроль'
}

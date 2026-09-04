import type {
    ModelCatalogEntry,
    ModelRouteDecision,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../api/modelApi'
import type { OrganizationDirectoryItem } from '../../../api/organizationApi'
import {
    formatDateTime,
    formatUsd,
} from '../../../utils/format'

type RuntimeCardProps = {
    runtime: RuntimeModelStatus
    effectiveCatalog: ModelCatalogEntry[]
}

type PolicyCardProps = {
    policy: OrganizationModelPolicy
    catalog: ModelCatalogEntry[]
    organizationId: string
    isSuperAdmin: boolean
    organizationQuery: string
    organizationResults: OrganizationDirectoryItem[]
    organizationSearchPending: boolean
    onOrganizationQueryChange: (
        value: string,
    ) => void
    onOrganizationSearch: () =>
        void | Promise<void>
    onOrganizationSelect: (
        organization: OrganizationDirectoryItem,
    ) => void
    onEdit: () => void
}

type CatalogTableProps = {
    entries: ModelCatalogEntry[]
    effectiveEntries: ModelCatalogEntry[]
    runtime: RuntimeModelStatus
    canEdit: boolean
    onCreateVersion: (
        entry: ModelCatalogEntry,
    ) => void
}

type StatusPillProps = {
    tone:
        | 'success'
        | 'warning'
        | 'danger'
        | 'neutral'
    label: string
}

const PRICING_STATUS_LABELS: Record<
    string,
    string
> = {
    FREE: 'Бесплатно',
    CONFIGURED: 'Стоимость настроена',
    INCOMPLETE: 'Стоимость заполнена не полностью',
    UNPRICED: 'Стоимость не указана',
}

const RETENTION_STATUS_LABELS: Record<
    string,
    string
> = {
    NOT_DECLARED: 'Не указано',
    STANDARD: 'Стандартное хранение',
    ZERO_DATA_RETENTION:
        'Данные не сохраняются',
    CUSTOM: 'Особые условия хранения',
}

const TRAINING_STATUS_LABELS: Record<
    string,
    string
> = {
    NOT_DECLARED: 'Условия обучения не указаны',
    NOT_USED: 'Не используется для обучения',
    MAY_BE_USED: 'Может использоваться для обучения',
    CONTRACTUAL_NO_TRAINING:
        'Обучение на данных запрещено договором',
}

const LIFECYCLE_LABELS: Record<
    string,
    string
> = {
    ACTIVE: 'Активна',
    DEPRECATED: 'Устаревает',
    DISABLED: 'Отключена',
    RETIRED: 'Выведена из эксплуатации',
}

const BUDGET_ENFORCEMENT_LABELS: Record<
    string,
    string
> = {
    SOFT: 'Мягкий контроль',
    HARD: 'Жёсткий контроль',
}

const MONTHLY_COST_STATE_LABELS: Record<
    string,
    string
> = {
    NOT_EVALUATED: 'Не рассчитывалось',
    KNOWN: 'Полные данные',
    UNKNOWN: 'Есть неизвестная стоимость',
}

const CAPABILITY_LABELS: Record<
    string,
    string
> = {
    TOOLS: 'Инструменты',
    VISION: 'Изображения',
    STRUCTURED_OUTPUT:
        'Структурированный ответ',
}

const CATALOG_SOURCE_LABELS: Record<
    string,
    string
> = {
    MANUAL: 'Создана вручную',
    RUNTIME_IMPORT: 'Добавлена из подключения',
    MIGRATED: 'Перенесена из прежней конфигурации',
}

const ROUTE_REASON_LABELS: Record<
    string,
    string
> = {
    REQUESTED_MODEL:
        'Выбрана явно запрошенная модель',
    POLICY_DEFAULT:
        'Выбрана модель по умолчанию',
    RUNTIME_ONLY_MATCH:
        'Совпала с подключённой моделью',
    LEGACY_RUNTIME_FALLBACK:
        'Использована подключённая модель',
    MODEL_NOT_ALLOWED:
        'Модель не входит в список разрешённых',
    MODEL_DENIED:
        'Модель запрещена правилами',
    MODEL_NOT_FOUND:
        'Модель не найдена',
    MODEL_DISABLED:
        'Модель отключена',
    RUNTIME_MISMATCH:
        'Модель не совпадает с подключённой',
    CAPABILITY_UNSUPPORTED:
        'Нужная возможность не поддерживается',
    INPUT_LIMIT_EXCEEDED:
        'Превышен лимит входных токенов',
    OUTPUT_LIMIT_EXCEEDED:
        'Превышен лимит выходных токенов',
    PRICING_INCOMPLETE:
        'Недостаточно данных о стоимости',
    TRAINING_POLICY_UNSATISFIED:
        'Не выполнено требование по обучению',
    RETENTION_POLICY_UNSATISFIED:
        'Не выполнено требование по хранению данных',
    REQUEST_COST_LIMIT_EXCEEDED:
        'Превышен лимит стоимости запроса',
    MONTHLY_BUDGET_EXCEEDED:
        'Превышен месячный бюджет',
    MONTHLY_BUDGET_UNVERIFIABLE:
        'Невозможно надёжно проверить месячный бюджет',
}

function InfoHint({
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

function StatusPill({
    tone,
    label,
}: StatusPillProps) {
    return (
        <span
            className={`models-status models-status--${tone}`}
        >
            {label}
        </span>
    )
}

function formatEnumFallback(
    value: string | null | undefined,
) {
    if (!value) {
        return '—'
    }

    return value
        .toLowerCase()
        .split('_')
        .map((part) =>
            part
                ? part[0]?.toUpperCase()
                    + part.slice(1)
                : part,
        )
        .join(' ')
}

function formatPricingStatus(
    value: string,
) {
    return (
        PRICING_STATUS_LABELS[value]
        ?? formatEnumFallback(value)
    )
}

function formatRetentionStatus(
    value: string,
) {
    return (
        RETENTION_STATUS_LABELS[value]
        ?? formatEnumFallback(value)
    )
}

function formatTrainingUseStatus(
    value: string,
) {
    return (
        TRAINING_STATUS_LABELS[value]
        ?? formatEnumFallback(value)
    )
}

function formatLifecycle(
    value: string,
) {
    return (
        LIFECYCLE_LABELS[value]
        ?? formatEnumFallback(value)
    )
}

function formatBudgetEnforcement(
    value: string | null,
) {
    if (!value) {
        return '—'
    }

    return (
        BUDGET_ENFORCEMENT_LABELS[value]
        ?? formatEnumFallback(value)
    )
}

function formatMonthlyCostState(
    value: string,
) {
    return (
        MONTHLY_COST_STATE_LABELS[value]
        ?? formatEnumFallback(value)
    )
}

function formatRuntimeCapabilities(
    runtime: RuntimeModelStatus,
) {
    const result: string[] = ['Текст']

    if (runtime.toolsSupported) {
        result.push('Инструменты')
    }

    if (runtime.visionSupported) {
        result.push('Изображения')
    }

    if (
        runtime.structuredOutputSupported
    ) {
        result.push(
            'Структурированный ответ',
        )
    }

    return result.join(', ')
}

function formatCatalogCapabilities(
    entry: ModelCatalogEntry,
) {
    if (entry.capabilities.length === 0) {
        return 'Текст'
    }

    return [
        'Текст',
        ...entry.capabilities.map(
            (capability) =>
                CAPABILITY_LABELS[capability]
                ?? formatEnumFallback(capability),
        ),
    ].join(', ')
}

function formatPolicyRequirements(
    policy: OrganizationModelPolicy,
) {
    const requirements: string[] = []

    if (
        policy.requireCompletePricing
    ) {
        requirements.push(
            'Полные данные о стоимости',
        )
    }

    if (
        policy.requireNoTraining
    ) {
        requirements.push(
            'Без обучения на данных',
        )
    }

    if (
        policy.requireZeroDataRetention
    ) {
        requirements.push(
            'Без хранения данных',
        )
    }

    if (requirements.length === 0) {
        return 'Базовые требования'
    }

    return requirements.join(' · ')
}

function formatRouteReason(
    reason: string,
) {
    return (
        ROUTE_REASON_LABELS[reason]
        ?? formatEnumFallback(reason)
    )
}

export function RuntimeCard({
    runtime,
    effectiveCatalog,
}: RuntimeCardProps) {
    const catalogMatch =
        effectiveCatalog.find(
            (entry) =>
                entry.provider === runtime.provider
                && entry.providerModelId
                    === runtime.model,
        ) ?? null

    return (
        <section className="models-card models-card--runtime">
            <div className="models-card__heading">
                <div>
                    <span>ПОДКЛЮЧЕНИЕ</span>
                    <h2>Подключённая модель</h2>
                </div>

                <StatusPill
                    tone={
                        runtime.enabled
                            ? 'success'
                            : 'danger'
                    }
                    label={
                        runtime.enabled
                            ? 'Активна'
                            : 'Отключена'
                    }
                />
            </div>

            <dl className="models-kv-grid">
                <div>
                    <dt>
                        <span className="models-label-row">
                            Провайдер / модель
                            <InfoHint text="Модель, к которой сейчас подключён сервер SafeAI." />
                        </span>
                    </dt>
                    <dd>
                        {runtime.provider} / {runtime.model}
                    </dd>
                    <small>
                        Один активный провайдер
                    </small>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            Лимиты токенов
                            <InfoHint text="Максимальный объём входного контекста и ответа для подключённой модели." />
                        </span>
                    </dt>
                    <dd>
                        {runtime.maxInputTokens.toLocaleString(
                            'ru-RU',
                        )}
                        {' / '}
                        {runtime.maxOutputTokens.toLocaleString(
                            'ru-RU',
                        )}
                    </dd>
                    <small>
                        вход / выход
                    </small>
                </div>

                <div>
                    <dt>Возможности</dt>
                    <dd>
                        {formatRuntimeCapabilities(
                            runtime,
                        )}
                    </dd>
                </div>

                <div>
                    <dt>Стоимость</dt>
                    <dd>
                        {formatPricingStatus(
                            runtime.pricingStatus,
                        )}
                    </dd>
                    <small>
                        {runtime.pricingVersion
                            ?? 'Версия тарифа не указана'}
                    </small>
                </div>

                <div>
                    <dt>Хранение данных</dt>
                    <dd>
                        {formatRetentionStatus(
                            runtime.dataRetentionStatus,
                        )}
                    </dd>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            Запись в каталоге
                            <InfoHint text="Показывает, есть ли в каталоге действующая запись для подключённой модели." />
                        </span>
                    </dt>
                    <dd>
                        {catalogMatch
                            ? `${catalogMatch.modelKey} · версия ${catalogMatch.version}`
                            : 'Действующей записи пока нет'}
                    </dd>
                </div>
            </dl>
        </section>
    )
}

export function PolicyCard({
    policy,
    catalog,
    organizationId,
    isSuperAdmin,
    organizationQuery,
    organizationResults,
    organizationSearchPending,
    onOrganizationQueryChange,
    onOrganizationSearch,
    onOrganizationSelect,
    onEdit,
}: PolicyCardProps) {
    const defaultEntry =
        policy.defaultModelKey
            ? catalog.find(
                (entry) =>
                    entry.modelKey
                    === policy.defaultModelKey,
            ) ?? null
            : null

    return (
        <section className="models-card models-card--policy">
            <div className="models-card__heading">
                <div>
                    <span>ПРАВИЛА ОРГАНИЗАЦИИ</span>
                    <h2>Доступ и ограничения</h2>
                </div>

                <StatusPill
                    tone={
                        policy.configured
                        && policy.enabled
                            ? 'success'
                            : policy.configured
                                ? 'warning'
                                : 'neutral'
                    }
                    label={
                        policy.configured
                            ? policy.enabled
                                ? `Версия ${policy.version} · включены`
                                : `Версия ${policy.version} · выключены`
                            : 'Не настроены'
                    }
                />
            </div>

            {isSuperAdmin && (
                <form
                    className="models-org-picker"
                    onSubmit={(event) => {
                        event.preventDefault()
                        void onOrganizationSearch()
                    }}
                >
                    <label>
                        Организация
                        <div className="models-org-picker__input-row">
                            <input
                                type="search"
                                value={organizationQuery}
                                placeholder="Название или UUID"
                                onChange={(event) => {
                                    onOrganizationQueryChange(
                                        event.target.value,
                                    )
                                }}
                            />
                            <button
                                type="submit"
                                disabled={
                                    organizationSearchPending
                                }
                            >
                                Найти
                            </button>
                        </div>
                    </label>

                    <small>
                        ID выбранной организации:{' '}
                        {organizationId}
                    </small>

                    {organizationResults.length > 0 && (
                        <div className="models-org-results">
                            {organizationResults.map(
                                (
                                    organization,
                                ) => (
                                    <button
                                        key={
                                            organization.id
                                        }
                                        type="button"
                                        onClick={() => {
                                            onOrganizationSelect(
                                                organization,
                                            )
                                        }}
                                    >
                                        <strong>
                                            {
                                                organization.name
                                            }
                                        </strong>
                                        <small>
                                            {
                                                organization.id
                                            }
                                            {' · '}
                                            {organization.enabled
                                                ? 'активна'
                                                : 'отключена'}
                                        </small>
                                    </button>
                                ),
                            )}
                        </div>
                    )}
                </form>
            )}

            <dl className="models-kv-grid">
                <div>
                    <dt>Модель по умолчанию</dt>
                    <dd>
                        {policy.defaultModelKey
                            ?? 'Подключённая модель'}
                    </dd>
                    {defaultEntry && (
                        <small>
                            {defaultEntry.provider}
                            /
                            {
                                defaultEntry.providerModelId
                            }
                        </small>
                    )}
                </div>

                <div>
                    <dt>Списки доступа</dt>
                    <dd>
                        Разрешено:{' '}
                        {
                            policy.allowModelKeys
                                .length
                        }
                        {' · '}
                        Запрещено:{' '}
                        {
                            policy.denyModelKeys
                                .length
                        }
                    </dd>
                </div>

                <div>
                    <dt>Стоимость запроса</dt>
                    <dd>
                        {policy.maxRequestCostUsd
                            ? formatUsd(
                                policy.maxRequestCostUsd,
                            )
                            : 'Без лимита'}
                    </dd>
                </div>

                <div>
                    <dt>Бюджет на месяц</dt>
                    <dd>
                        {policy.monthlyBudgetUsd
                            ? formatUsd(
                                policy.monthlyBudgetUsd,
                            )
                            : 'Не задан'}
                    </dd>
                    <small>
                        {policy.monthlyBudgetUsd
                            ? formatBudgetEnforcement(
                                policy.budgetEnforcement,
                            )
                            : 'Контроль бюджета выключен'}
                    </small>
                </div>

                <div>
                    <dt>Требования к данным</dt>
                    <dd>
                        {formatPolicyRequirements(
                            policy,
                        )}
                    </dd>
                </div>

                <div>
                    <dt>Лимиты токенов</dt>
                    <dd>
                        {policy.maxInputTokens?.toLocaleString(
                            'ru-RU',
                        ) ?? '—'}
                        {' / '}
                        {policy.maxOutputTokens?.toLocaleString(
                            'ru-RU',
                        ) ?? '—'}
                    </dd>
                    <small>
                        вход / выход
                    </small>
                </div>
            </dl>

            <div className="models-policy-card__footer">
                <p className="models-policy-card__note">
                    Этот блок показывает настройки из окна «Правила использования моделей».
                    Блок «Подключённая модель» слева определяется фактическим подключением сервера
                    и настраивается отдельно.
                </p>

                <button
                    type="button"
                    className="btn-primary models-policy-action"
                    onClick={onEdit}
                >
                    {policy.configured
                        ? 'Изменить правила'
                        : 'Настроить правила'}
                </button>
            </div>
        </section>
    )
}

export function CatalogTable({
    entries,
    effectiveEntries,
    runtime,
    canEdit,
    onCreateVersion,
}: CatalogTableProps) {
    return (
        <table className="models-catalog-table">
            <thead>
                <tr>
                    <th>Модель</th>
                    <th>Подключение</th>
                    <th>Статус</th>
                    <th>Лимиты</th>
                    <th>Возможности</th>
                    <th>Стоимость</th>
                    <th>Данные</th>
                    <th>Действует с</th>
                    {canEdit && (
                        <th>Действия</th>
                    )}
                </tr>
            </thead>

            <tbody>
                {entries.map((entry) => {
                    const effectiveEntry =
                        effectiveEntries.find(
                            (
                                candidate,
                            ) =>
                                candidate.modelKey
                                === entry.modelKey,
                        ) ?? null

                    const isEffectiveVersion =
                        effectiveEntry?.id
                        === entry.id

                    const executable =
                        isEffectiveVersion
                        && (entry.lifecycle
                            === 'ACTIVE'
                            || entry.lifecycle
                                === 'DEPRECATED')
                        && entry.provider
                            === runtime.provider
                        && entry.providerModelId
                            === runtime.model

                    const scheduled =
                        new Date(
                            entry.effectiveFrom,
                        ).getTime()
                        > Date.now()

                    return (
                        <tr key={entry.id}>
                            <td>
                                <strong>
                                    {
                                        entry.displayName
                                    }
                                </strong>
                                <code>
                                    {
                                        entry.modelKey
                                    }
                                </code>
                                <small>
                                    Версия{' '}
                                    {
                                        entry.version
                                    }
                                    {' · '}
                                    {
                                        CATALOG_SOURCE_LABELS[
                                            entry.source
                                        ]
                                        ?? formatEnumFallback(
                                            entry.source,
                                        )
                                    }
                                </small>
                            </td>

                            <td>
                                <span>
                                    {
                                        entry.provider
                                    }
                                    {' / '}
                                    {
                                        entry.providerModelId
                                    }
                                </span>

                                <StatusPill
                                    tone={
                                        executable
                                            ? 'success'
                                            : scheduled
                                                ? 'warning'
                                                : 'neutral'
                                    }
                                    label={
                                        executable
                                            ? 'Используется сейчас'
                                            : scheduled
                                                ? 'Запланирована'
                                                : isEffectiveVersion
                                                    ? 'Действует, но не подключена'
                                                    : effectiveEntry
                                                        ? `Сейчас действует версия ${effectiveEntry.version}`
                                                        : 'Пока не действует'
                                    }
                                />
                            </td>

                            <td>
                                <StatusPill
                                    tone={
                                        entry.lifecycle
                                            === 'ACTIVE'
                                            ? 'success'
                                            : entry.lifecycle
                                                === 'DEPRECATED'
                                                ? 'warning'
                                                : entry.lifecycle
                                                    === 'DISABLED'
                                                    ? 'danger'
                                                    : 'neutral'
                                    }
                                    label={formatLifecycle(
                                        entry.lifecycle,
                                    )}
                                />
                            </td>

                            <td>
                                {entry.maxInputTokens.toLocaleString(
                                    'ru-RU',
                                )}
                                {' / '}
                                {entry.maxOutputTokens.toLocaleString(
                                    'ru-RU',
                                )}
                                <small>
                                    вход / выход
                                </small>
                            </td>

                            <td>
                                {formatCatalogCapabilities(
                                    entry,
                                )}
                            </td>

                            <td>
                                <strong>
                                    {formatPricingStatus(
                                        entry.pricingStatus,
                                    )}
                                </strong>
                                <small>
                                    {entry.pricingComplete
                                        ? 'Данные о стоимости полные'
                                        : 'Данные о стоимости неполные'}
                                    {entry.pricingVersion
                                        ? ` · ${entry.pricingVersion}`
                                        : ''}
                                </small>
                            </td>

                            <td>
                                <span>
                                    {formatRetentionStatus(
                                        entry.retentionStatus,
                                    )}
                                </span>
                                <small>
                                    {formatTrainingUseStatus(
                                        entry.trainingUseStatus,
                                    )}
                                </small>
                            </td>

                            <td>
                                {formatDateTime(
                                    entry.effectiveFrom,
                                )}
                            </td>

                            {canEdit && (
                                <td>
                                    <button
                                        type="button"
                                        onClick={() => {
                                            onCreateVersion(
                                                entry,
                                            )
                                        }}
                                    >
                                        Новая версия
                                    </button>
                                </td>
                            )}
                        </tr>
                    )
                })}
            </tbody>
        </table>
    )
}

export function RouteDecisionEvidence({
    decision,
}: {
    decision: ModelRouteDecision
}) {
    return (
        <section className="models-route-evidence">
            <div className="models-route-evidence__heading">
                <div>
                    <strong>
                        {decision.id}
                    </strong>
                    <small>
                        {formatDateTime(
                            decision.createdAt,
                        )}
                    </small>
                </div>

                <StatusPill
                    tone={
                        decision.outcome
                        === 'ALLOWED'
                            ? 'success'
                            : 'danger'
                    }
                    label={`${decision.outcome === 'ALLOWED' ? 'Разрешено' : 'Отклонено'} · ${formatRouteReason(decision.reason)}`}
                />
            </div>

            <dl className="models-kv-grid">
                <div>
                    <dt>Выбранная модель</dt>
                    <dd>
                        {decision.selectedModelKey
                            ?? '—'}
                    </dd>
                    <small>
                        {decision.selectedProvider
                            ?? '—'}
                        {' / '}
                        {decision.selectedProviderModelId
                            ?? '—'}
                    </small>
                </div>

                <div>
                    <dt>Версии настроек</dt>
                    <dd>
                        {decision.selectedCatalogVersion
                            != null
                            ? `Каталог ${decision.selectedCatalogVersion}`
                            : 'Каталог —'}
                        {' · '}
                        {decision.policyVersion
                            != null
                            ? `Правила ${decision.policyVersion}`
                            : 'Правила —'}
                    </dd>
                </div>

                <div>
                    <dt>Оценка токенов</dt>
                    <dd>
                        {decision.estimatedInputTokens?.toLocaleString(
                            'ru-RU',
                        ) ?? '—'}
                        {' / '}
                        {decision.estimatedOutputTokens?.toLocaleString(
                            'ru-RU',
                        ) ?? '—'}
                    </dd>
                    <small>вход / выход</small>
                </div>

                <div>
                    <dt>Оценка стоимости</dt>
                    <dd>
                        {decision.estimatedMaxCostUsd
                            ? formatUsd(
                                decision.estimatedMaxCostUsd,
                            )
                            : 'Неизвестно'}
                    </dd>
                    <small>
                        Данные о стоимости:{' '}
                        {decision.pricingComplete
                            ? 'полные'
                            : 'неполные'}
                    </small>
                </div>

                <div>
                    <dt>Месячный бюджет</dt>
                    <dd>
                        {decision.monthlyBudgetUsd
                            ? formatUsd(
                                decision.monthlyBudgetUsd,
                            )
                            : '—'}
                    </dd>
                    <small>
                        Учтено:{' '}
                        {decision.monthlySpentUsd
                            ? formatUsd(
                                decision.monthlySpentUsd,
                            )
                            : '—'}
                        {' · '}
                        Состояние:{' '}
                        {formatMonthlyCostState(
                            decision.monthlyCostState,
                        )}
                    </small>
                </div>

                <div>
                    <dt>Запрос чата</dt>
                    <dd>
                        {decision.chatTurnId
                            ?? 'Запрос был отклонён до запуска модели'}
                    </dd>
                </div>
            </dl>

            <code className="models-route-evidence__hash">
                Версия контроля целостности: v
                {decision.decisionIntegrityVersion}
                {' · '}
                Контрольная сумма:{' '}
                {decision.decisionSha256}
            </code>
        </section>
    )
}

import type {
    ModelCatalogEntry,
    ModelRouteDecision,
    OrganizationModelPolicy,
    RuntimeModelProbe,
    RuntimeModelProbeStatus,
    RuntimeModelStatus,
} from '../../../api/modelApi'
import type {
    OrganizationDirectoryItem,
} from '../../../api/organizationApi'
import {
    formatDateTime,
    formatUsd,
} from '../../../utils/format'

type StatusTone =
    | 'success'
    | 'warning'
    | 'danger'
    | 'neutral'

function StatusPill({
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

function enumLabel(
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

function probeLabel(
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

function probeTone(
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

function runtimeHealthLabel(
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

function runtimeCapabilities(
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

export function RuntimeCard({
    runtime,
    effectiveCatalog,
    probe,
    probePending,
    canProbe,
    onProbe,
}: {
    runtime: RuntimeModelStatus
    effectiveCatalog: ModelCatalogEntry[]
    probe: RuntimeModelProbe | null
    probePending: boolean
    canProbe: boolean
    onProbe: () => void
}) {
    const catalogMatch =
        effectiveCatalog.find(
            (entry) =>
                (
                    entry.lifecycle === 'ACTIVE'
                    || entry.lifecycle === 'DEPRECATED'
                )
                && entry.provider === runtime.provider
                && entry.providerModelId === runtime.model,
        ) ?? null

    return (
        <section className="models-card models-card--runtime">
            <div className="models-card__heading">
                <div>
                    <span>ПОДКЛЮЧЕНИЕ</span>
                    <h2>Подключённая модель</h2>
                </div>

                <StatusPill
                    tone={runtime.enabled ? 'success' : 'danger'}
                    label={runtime.enabled ? 'Включена' : 'Отключена'}
                />
            </div>

            <dl className="models-kv-grid">
                <div>
                    <dt>
                        <span className="models-label-row">
                            Провайдер / модель
                            <InfoHint text="Фактическая backend runtime-конфигурация. Policy организации её не переключает." />
                        </span>
                    </dt>
                    <dd>
                        {runtime.provider}
                        {' / '}
                        {runtime.model}
                    </dd>
                    <small>Один фиксированный провайдер</small>
                </div>

                <div>
                    <dt>Состояние конфигурации</dt>
                    <dd>
                        {runtime.enabled ? 'Включена' : 'Отключена'}
                    </dd>
                    <small>Это не network health-check</small>
                </div>

                <div>
                    <dt>Проверка доступности</dt>
                    <dd>
                        {runtimeHealthLabel(runtime, probe)}
                    </dd>
                    <small>
                        {probe
                            ? `${formatDateTime(probe.checkedAt)} · ${probe.latencyMs.toLocaleString('ru-RU')} мс`
                            : 'Соединение отдельно ещё не проверялось'}
                    </small>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            Лимиты токенов
                            <InfoHint text="Максимальный вход и выход физически настроенной модели." />
                        </span>
                    </dt>
                    <dd>
                        {runtime.maxInputTokens.toLocaleString('ru-RU')}
                        {' / '}
                        {runtime.maxOutputTokens.toLocaleString('ru-RU')}
                    </dd>
                    <small>вход / выход</small>
                </div>

                <div>
                    <dt>Возможности</dt>
                    <dd>{runtimeCapabilities(runtime)}</dd>
                </div>

                <div>
                    <dt>Стоимость</dt>
                    <dd>
                        {runtime.pricingStatus === 'FREE'
                            ? 'Бесплатно'
                            : enumLabel(runtime.pricingStatus)}
                    </dd>
                    <small>
                        {runtime.pricingVersion
                            ?? 'Версия стоимости не указана'}
                    </small>
                </div>

                <div>
                    <dt>Хранение данных</dt>
                    <dd>
                        {runtime.dataRetentionStatus === 'NOT_DECLARED'
                            ? 'Не указано'
                            : enumLabel(runtime.dataRetentionStatus)}
                    </dd>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            Запись в каталоге
                            <InfoHint text="Действующая catalog version, совпадающая с фактическим provider/model." />
                        </span>
                    </dt>
                    <dd>
                        {catalogMatch
                            ? `${catalogMatch.modelKey} · версия ${catalogMatch.version}`
                            : 'Действующей записи пока нет'}
                    </dd>
                </div>
            </dl>

            {canProbe && (
                <div className="models-runtime-probe">
                    <div>
                        <strong>Проверка соединения</strong>
                        <small>
                            Metadata-only: без prompt, history,
                            RAG и пользовательских данных.
                        </small>
                    </div>

                    <button
                        type="button"
                        disabled={probePending}
                        onClick={onProbe}
                    >
                        {probePending
                            ? 'Проверяем...'
                            : 'Проверить доступность'}
                    </button>
                </div>
            )}

            {probe && (
                <div
                    className="models-runtime-probe-result"
                    role="status"
                >
                    <StatusPill
                        tone={probeTone(probe.status)}
                        label={probeLabel(probe.status)}
                    />
                    <span>
                        {probe.message}
                        {probe.httpStatus !== null
                            ? ` · HTTP ${probe.httpStatus}`
                            : ''}
                    </span>
                </div>
            )}
        </section>
    )
}

export function PolicyCard({
    policy,
    catalog,
    organizationId,
    organizationName,
    isSuperAdmin,
    organizationQuery,
    organizationResults,
    organizationSearchPending,
    onOrganizationQueryChange,
    onOrganizationSearch,
    onOrganizationSelect,
    onEdit,
}: {
    policy: OrganizationModelPolicy
    catalog: ModelCatalogEntry[]
    organizationId: string
    organizationName: string | null
    isSuperAdmin: boolean
    organizationQuery: string
    organizationResults: OrganizationDirectoryItem[]
    organizationSearchPending: boolean
    onOrganizationQueryChange: (value: string) => void
    onOrganizationSearch: () => void | Promise<void>
    onOrganizationSelect: (organization: OrganizationDirectoryItem) => void
    onEdit: () => void
}) {
    const defaultEntry =
        policy.defaultModelKey
            ? catalog.find(
                (entry) => entry.modelKey === policy.defaultModelKey,
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
                        policy.configured && policy.enabled
                            ? 'success'
                            : policy.configured
                                ? 'warning'
                                : 'neutral'
                    }
                    label={
                        policy.configured
                            ? `Версия ${policy.version} · ${policy.enabled ? 'включены' : 'выключены'}`
                            : 'Не настроены'
                    }
                />
            </div>

            <div className="models-policy-organization-summary">
                <span>Организация</span>
                <strong>
                    {organizationName ?? 'Текущая организация'}
                </strong>
                <code>{organizationId}</code>
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
                        Выбрать другую организацию
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
                                disabled={organizationSearchPending}
                            >
                                {organizationSearchPending
                                    ? 'Поиск...'
                                    : 'Найти'}
                            </button>
                        </div>
                    </label>

                    {organizationResults.length > 0 && (
                        <div className="models-org-results">
                            {organizationResults.map((organization) => (
                                <button
                                    key={organization.id}
                                    type="button"
                                    onClick={() => {
                                        onOrganizationSelect(organization)
                                    }}
                                >
                                    <strong>{organization.name}</strong>
                                    <small>{organization.id}</small>
                                </button>
                            ))}
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
                            {' / '}
                            {defaultEntry.providerModelId}
                        </small>
                    )}
                </div>

                <div>
                    <dt>Списки доступа</dt>
                    <dd>
                        Разрешено: {policy.allowModelKeys.length}
                        {' · '}
                        Запрещено: {policy.denyModelKeys.length}
                    </dd>
                </div>

                <div>
                    <dt>Стоимость запроса</dt>
                    <dd>
                        {policy.maxRequestCostUsd === null
                            ? 'Без лимита'
                            : formatUsd(policy.maxRequestCostUsd)}
                    </dd>
                </div>

                <div>
                    <dt>Бюджет на месяц</dt>
                    <dd>
                        {policy.monthlyBudgetUsd === null
                            ? 'Не задан'
                            : formatUsd(policy.monthlyBudgetUsd)}
                    </dd>
                    <small>
                        {policy.monthlyBudgetUsd === null
                            ? 'Контроль бюджета выключен'
                            : policy.budgetEnforcement === 'HARD'
                                ? 'Жёсткий контроль'
                                : 'Мягкий контроль'}
                    </small>
                </div>

                <div>
                    <dt>Требования к данным</dt>
                    <dd>
                        {[
                            policy.requireCompletePricing
                                ? 'Полная стоимость'
                                : null,
                            policy.requireNoTraining
                                ? 'Без обучения'
                                : null,
                            policy.requireZeroDataRetention
                                ? 'Без хранения'
                                : null,
                        ].filter(Boolean).join(' · ')
                            || 'Базовые требования'}
                    </dd>
                </div>

                <div>
                    <dt>Лимиты токенов</dt>
                    <dd>
                        {policy.maxInputTokens?.toLocaleString('ru-RU') ?? '—'}
                        {' / '}
                        {policy.maxOutputTokens?.toLocaleString('ru-RU') ?? '—'}
                    </dd>
                    <small>вход / выход</small>
                </div>
            </dl>

            <div className="models-policy-card__footer">
                <p className="models-policy-card__note">
                    Policy ограничивает использование моделей этой
                    организацией. Физическое подключение сервера слева
                    настраивается отдельно.
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
}: {
    entries: ModelCatalogEntry[]
    effectiveEntries: ModelCatalogEntry[]
    runtime: RuntimeModelStatus
    canEdit: boolean
    onCreateVersion: (entry: ModelCatalogEntry) => void
}) {
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
                    {canEdit && <th>Действия</th>}
                </tr>
            </thead>

            <tbody>
                {entries.map((entry) => {
                    const effectiveEntry =
                        effectiveEntries.find(
                            (candidate) =>
                                candidate.modelKey === entry.modelKey,
                        ) ?? null
                    const isEffectiveVersion =
                        effectiveEntry?.id === entry.id
                    const executable =
                        isEffectiveVersion
                        && (
                            entry.lifecycle === 'ACTIVE'
                            || entry.lifecycle === 'DEPRECATED'
                        )
                        && entry.provider === runtime.provider
                        && entry.providerModelId === runtime.model
                    const effectiveFromMs =
                        new Date(entry.effectiveFrom).getTime()
                    const scheduled =
                        Number.isFinite(effectiveFromMs)
                        && effectiveFromMs > Date.now()

                    const connectionLabel = executable
                        ? 'Используется сейчас'
                        : scheduled
                            ? `Запланирована с ${formatDateTime(entry.effectiveFrom)}`
                            : isEffectiveVersion
                                ? 'Действует, но runtime сейчас другой'
                                : effectiveEntry
                                    ? `Сейчас действует версия ${effectiveEntry.version}`
                                    : 'Сейчас не используется'

                    return (
                        <tr key={entry.id}>
                            <td>
                                <strong>{entry.displayName}</strong>
                                <code>{entry.modelKey}</code>
                                <small>
                                    Версия {entry.version}
                                    {' · '}
                                    {entry.source === 'RUNTIME_IMPORT'
                                        ? 'Добавлена из подключения'
                                        : enumLabel(entry.source)}
                                </small>
                            </td>

                            <td>
                                <span>
                                    {entry.provider}
                                    {' / '}
                                    {entry.providerModelId}
                                </span>
                                <StatusPill
                                    tone={
                                        executable
                                            ? 'success'
                                            : scheduled
                                                ? 'warning'
                                                : 'neutral'
                                    }
                                    label={connectionLabel}
                                />
                                {scheduled && effectiveEntry && (
                                    <small>
                                        До этого момента routing использует
                                        версию {effectiveEntry.version}
                                    </small>
                                )}
                            </td>

                            <td>
                                <StatusPill
                                    tone={
                                        entry.lifecycle === 'ACTIVE'
                                            ? 'success'
                                            : entry.lifecycle === 'DEPRECATED'
                                                ? 'warning'
                                                : entry.lifecycle === 'DISABLED'
                                                    ? 'danger'
                                                    : 'neutral'
                                    }
                                    label={enumLabel(entry.lifecycle)}
                                />
                            </td>

                            <td>
                                {entry.maxInputTokens.toLocaleString('ru-RU')}
                                {' / '}
                                {entry.maxOutputTokens.toLocaleString('ru-RU')}
                                <small>вход / выход</small>
                            </td>

                            <td>
                                {entry.capabilities.length === 0
                                    ? 'Текст'
                                    : `Текст · ${entry.capabilities.map(enumLabel).join(' · ')}`}
                            </td>

                            <td>
                                <strong>
                                    {entry.pricingStatus === 'FREE'
                                        ? 'Бесплатно'
                                        : enumLabel(entry.pricingStatus)}
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
                                    {entry.retentionStatus === 'NOT_DECLARED'
                                        ? 'Не указано'
                                        : enumLabel(entry.retentionStatus)}
                                </span>
                                <small>
                                    {enumLabel(entry.trainingUseStatus)}
                                </small>
                            </td>

                            <td>
                                {formatDateTime(entry.effectiveFrom)}
                            </td>

                            {canEdit && (
                                <td>
                                    <button
                                        type="button"
                                        onClick={() => {
                                            onCreateVersion(entry)
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

function routeReasonLabel(
    reason: ModelRouteDecision['reason'],
): string {
    const labels: Record<string, string> = {
        REQUESTED_MODEL: 'Использована явно запрошенная модель',
        POLICY_DEFAULT: 'Выбрана модель по умолчанию',
        RUNTIME_ONLY_MATCH: 'Совпала с подключённой моделью',
        LEGACY_RUNTIME_FALLBACK: 'Использован runtime fallback',
        MODEL_NOT_ALLOWED: 'Модель не входит в список разрешённых',
        MODEL_DENIED: 'Модель запрещена правилами',
        MODEL_NOT_FOUND: 'Модель не найдена',
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

export function RouteDecisionEvidence({
    decision,
}: {
    decision: ModelRouteDecision
}) {
    return (
        <section className="models-route-evidence">
            <div className="models-route-evidence__heading">
                <div>
                    <strong>{decision.id}</strong>
                    <small>{formatDateTime(decision.createdAt)}</small>
                </div>

                <StatusPill
                    tone={decision.outcome === 'ALLOWED' ? 'success' : 'danger'}
                    label={`${decision.outcome === 'ALLOWED' ? 'Разрешено' : 'Отклонено'} · ${routeReasonLabel(decision.reason)}`}
                />
            </div>

            <dl className="models-kv-grid">
                <div>
                    <dt>Выбранная модель</dt>
                    <dd>{decision.selectedModelKey ?? '—'}</dd>
                    <small>
                        {decision.selectedProvider ?? '—'}
                        {' / '}
                        {decision.selectedProviderModelId ?? '—'}
                    </small>
                </div>

                <div>
                    <dt>Версии настроек</dt>
                    <dd>
                        {decision.selectedCatalogVersion !== null
                            ? `Каталог ${decision.selectedCatalogVersion}`
                            : 'Каталог —'}
                        {' · '}
                        {decision.policyVersion !== null
                            ? `Правила ${decision.policyVersion}`
                            : 'Правила —'}
                    </dd>
                </div>

                <div>
                    <dt>Оценка input/output</dt>
                    <dd>
                        {decision.estimatedInputTokens?.toLocaleString('ru-RU') ?? '—'}
                        {' / '}
                        {decision.estimatedOutputTokens?.toLocaleString('ru-RU') ?? '—'}
                    </dd>
                    <small>
                        Исторические DB/wire-поля всё ещё называются tokens
                    </small>
                </div>

                <div>
                    <dt>Оценка стоимости</dt>
                    <dd>
                        {decision.estimatedMaxCostUsd === null
                            ? 'Неизвестно'
                            : formatUsd(decision.estimatedMaxCostUsd)}
                    </dd>
                    <small>
                        pricingComplete: {decision.pricingComplete ? 'да' : 'нет'}
                    </small>
                </div>

                <div>
                    <dt>Месячный бюджет</dt>
                    <dd>
                        {decision.monthlyBudgetUsd === null
                            ? 'Не применялся'
                            : formatUsd(decision.monthlyBudgetUsd)}
                    </dd>
                    <small>
                        {decision.monthlyCostState}
                        {' · '}
                        {decision.budgetEnforcement ?? '—'}
                    </small>
                </div>

                <div>
                    <dt>Причина</dt>
                    <dd>{routeReasonLabel(decision.reason)}</dd>
                    <small>{decision.reason}</small>
                </div>
            </dl>

            <div className="models-route-accounting">
                <h3>V48 governance evidence</h3>

                <div className="models-route-accounting__grid">
                    <div>
                        <span>Integrity version</span>
                        <strong>
                            v{decision.decisionIntegrityVersion}
                        </strong>
                        <small>
                            Версия canonical evidence/hash-схемы,
                            а не версия AI-модели.
                        </small>
                    </div>

                    <div>
                        <span>Input accounting</span>
                        <strong>
                            {decision.inputAccountingVersion
                                ?? 'Историческая V1/V2 decision'}
                        </strong>
                        <small>
                            Версия governance-алгоритма input units.
                            Это не tokenizer провайдера.
                        </small>
                    </div>

                    <div>
                        <span>Additional input envelope</span>
                        <strong>
                            {decision.additionalInputUnitUpperBound ?? '—'}
                        </strong>
                        <small>
                            Верхняя граница system/RAG/tool input
                            до provider I/O.
                        </small>
                    </div>
                </div>

                <code className="models-route-evidence__hash">
                    SHA-256: {decision.decisionSha256}
                </code>

                <p>
                    Для новой V48 decision ожидается integrity v3,
                    непустая accounting version, неотрицательный envelope
                    и 64-символьный lowercase SHA-256. Исторические V1/V2
                    решения не переписываются.
                </p>
            </div>
        </section>
    )
}

/* ============================================================
   frontend/src/components/admin/models/ModelControlPlaneViews.tsx
   ============================================================ */
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

function runtimeModeLabel(
    routingMode: string,
): string {
    switch (routingMode) {
        case 'SINGLE_PROVIDER_STATIC':
            return 'Один фиксированный провайдер и модель'
        default:
            return enumLabel(routingMode)
    }
}

function lifecycleLabel(
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

function lifecycleTone(
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

function retentionLabel(
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

function trainingUseLabel(
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

function pricingStatusLabel(
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

function catalogSourceLabel(
    source: ModelCatalogEntry['source'],
): string {
    switch (source) {
        case 'RUNTIME_IMPORT':
            return 'Добавлена из Runtime'
        case 'MANUAL':
            return 'Создана вручную'
        case 'MIGRATED':
            return 'Перенесена миграцией'
    }
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

/**
 * В runtime card показываются только возможности, которые backend
 * считает реально исполнимыми data plane.
 *
 * Catalog capability metadata не используется здесь как доказательство
 * runtime-поддержки. Пока backend возвращает false для TOOLS/VISION/
 * STRUCTURED_OUTPUT, UI показывает только реально исполнимые возможности.
 */
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

function policyAccessSummary(
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

function policyDataRequirements(
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

function policyTokenLimits(
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

function catalogCapabilities(
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
                            <InfoHint text="Фактическая конфигурация Runtime на сервере. Правила организации её не переключают." />
                        </span>
                    </dt>
                    <dd>
                        {runtime.provider}
                        {' / '}
                        {runtime.model}
                    </dd>
                    <small>Физическая конфигурация на сервере</small>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            Режим Runtime
                            <InfoHint text="Текущий способ физического исполнения моделей. Это не правила организации." />
                        </span>
                    </dt>
                    <dd>
                        {runtimeModeLabel(runtime.routingMode)}
                    </dd>
                    <small>
                        Динамический выбор физического провайдера и модели не используется
                    </small>
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
                    <dt>
                        <span className="models-label-row">
                            Исполняемые возможности
                            <InfoHint text="Показываются только возможности, которые Runtime действительно умеет выполнять. Сведения каталога сами по себе не включают возможность." />
                        </span>
                    </dt>
                    <dd>{runtimeCapabilities(runtime)}</dd>
                    <small>Недоступные возможности блокируются до обращения к провайдеру</small>
                </div>

                <div>
                    <dt>Стоимость</dt>
                    <dd>
                        {pricingStatusLabel(runtime.pricingStatus)}
                    </dd>
                    <small>
                        {runtime.pricingVersion
                            ?? 'Версия стоимости не указана'}
                    </small>
                </div>

                <div>
                    <dt>Хранение данных</dt>
                    <dd>
                        {retentionLabel(runtime.dataRetentionStatus)}
                    </dd>
                    <small>
                        Сведения об использовании данных для обучения в Runtime сейчас не предоставляются
                    </small>
                </div>

                <div>
                    <dt>
                        <span className="models-label-row">
                            Запись в каталоге
                            <InfoHint text="Действующая версия каталога, совпадающая с фактическим провайдером и моделью Runtime." />
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
                            Техническая проверка: без текста запроса, истории чата,
                            контекста базы знаний и пользовательских данных.
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
    runtime,
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
    runtime: RuntimeModelStatus
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
                    {organizationName ?? 'Название загружается…'}
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
                            ?? 'Подключённая Runtime-модель'}
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
                    <dt>
                        <span className="models-label-row">
                            Списки доступа
                            <InfoHint text="Если список разрешённых пуст, доступны все модели, которые не запрещены явно и проходят остальные проверки правил и Runtime." />
                        </span>
                    </dt>
                    <dd>
                        {policyAccessSummary(policy)}
                    </dd>
                    {policy.allowModelKeys.length === 0 && (
                        <small>
                            Список разрешённых не ограничивает выбор моделей
                        </small>
                    )}
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
                        {policyDataRequirements(policy)}
                    </dd>
                </div>

                <div>
                    <dt>Лимиты токенов</dt>
                    <dd>
                        {policyTokenLimits(policy)}
                    </dd>
                    <small>
                        Runtime: {' '}
                        {runtime.maxInputTokens.toLocaleString('ru-RU')}
                        {' / '}
                        {runtime.maxOutputTokens.toLocaleString('ru-RU')}
                    </small>
                </div>
            </dl>

            <div className="models-policy-card__footer">
                <p className="models-policy-card__note">
                    Правила ограничивают использование моделей этой
                    организацией. Физическое подключение Runtime
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
    onOpenHistory,
}: {
    entries: ModelCatalogEntry[]
    effectiveEntries: ModelCatalogEntry[]
    runtime: RuntimeModelStatus
    canEdit: boolean
    onCreateVersion: (entry: ModelCatalogEntry) => void
    onOpenHistory: (entry: ModelCatalogEntry) => void
}) {
    return (
        <table className="models-catalog-table">
            <thead>
                <tr>
                    <th>Модель</th>
                    <th>Runtime</th>
                    <th>Статус каталога</th>
                    <th>Лимиты</th>
                    <th>Возможности каталога</th>
                    <th>Стоимость</th>
                    <th>Политика данных</th>
                    <th>Действует с</th>
                    <th>Действия</th>
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

                    /*
                     * GET /catalog возвращает latest snapshot per modelKey,
                     * GET /catalog/effective — server-clock authoritative
                     * effective snapshot. Поэтому frontend не использует
                     * browser clock для решения "scheduled vs effective".
                     */
                    const scheduled =
                        !isEffectiveVersion

                    const runtimeMatches =
                        entry.provider === runtime.provider
                        && entry.providerModelId === runtime.model

                    const routeEligibleLifecycle =
                        entry.lifecycle === 'ACTIVE'
                        || entry.lifecycle === 'DEPRECATED'

                    const executableByCurrentRuntime =
                        isEffectiveVersion
                        && routeEligibleLifecycle
                        && runtimeMatches

                    const effectiveStateLabel =
                        isEffectiveVersion
                            ? 'Действует сейчас'
                            : 'Запланирована'

                    const effectiveStateTone: StatusTone =
                        isEffectiveVersion
                            ? 'success'
                            : 'warning'

                    return (
                        <tr key={entry.id}>
                            <td>
                                <strong>{entry.displayName}</strong>
                                <code>{entry.modelKey}</code>

                                <div className="models-catalog-version-flags">
                                    <StatusPill
                                        tone="neutral"
                                        label="Последняя версия"
                                    />

                                    {isEffectiveVersion && (
                                        <StatusPill
                                            tone="success"
                                            label="Действующая версия"
                                        />
                                    )}
                                </div>

                                <small>
                                    Версия {entry.version}
                                    {' · '}
                                    {catalogSourceLabel(entry.source)}
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
                                        runtimeMatches
                                            ? 'success'
                                            : 'neutral'
                                    }
                                    label={
                                        runtimeMatches
                                            ? 'Совпадает с Runtime'
                                            : 'Текущий Runtime другой'
                                    }
                                />

                                {executableByCurrentRuntime && (
                                    <small>
                                        Действующая версия физически
                                        исполнима текущим Runtime
                                    </small>
                                )}
                            </td>

                            <td>
                                <StatusPill
                                    tone={lifecycleTone(entry.lifecycle)}
                                    label={lifecycleLabel(entry.lifecycle)}
                                />
                            </td>

                            <td>
                                {entry.maxInputTokens.toLocaleString('ru-RU')}
                                {' / '}
                                {entry.maxOutputTokens.toLocaleString('ru-RU')}
                                <small>вход / выход</small>
                            </td>

                            <td>
                                {catalogCapabilities(entry)}
                                {entry.capabilities.length > 0 && (
                                    <small>
                                        Сведения каталога; фактическое исполнение
                                        проверяется Runtime отдельно
                                    </small>
                                )}
                            </td>

                            <td>
                                <strong>
                                    {pricingStatusLabel(entry.pricingStatus)}
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
                                    Хранение: {' '}
                                    {retentionLabel(entry.retentionStatus)}
                                </span>
                                <small>
                                    Обучение: {' '}
                                    {trainingUseLabel(entry.trainingUseStatus)}
                                </small>
                            </td>

                            <td>
                                {formatDateTime(entry.effectiveFrom)}

                                <div className="models-catalog-effective-state">
                                    <StatusPill
                                        tone={effectiveStateTone}
                                        label={effectiveStateLabel}
                                    />
                                </div>

                                {scheduled && effectiveEntry && (
                                    <small>
                                        Сейчас действует версия {' '}
                                        {effectiveEntry.version}
                                    </small>
                                )}

                                {scheduled && !effectiveEntry && (
                                    <small>
                                        До даты вступления в силу модель не участвует
                                        в маршрутизации
                                    </small>
                                )}
                            </td>

                            <td>
                                <div className="models-catalog-row-actions">
                                    <button
                                        type="button"
                                        onClick={() => {
                                            onOpenHistory(entry)
                                        }}
                                    >
                                        История версий
                                    </button>

                                    {canEdit && (
                                        <button
                                            type="button"
                                            onClick={() => {
                                                onCreateVersion(entry)
                                            }}
                                        >
                                            Новая версия
                                        </button>
                                    )}
                                </div>
                            </td>
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


function monthlyCostStateLabel(
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

function budgetEnforcementLabel(
    enforcement: ModelRouteDecision['budgetEnforcement'],
): string {
    if (enforcement === null) {
        return 'Не применялся'
    }

    return enforcement === 'HARD'
        ? 'Жёсткий контроль'
        : 'Мягкий контроль'
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
                    <dt>Оценка входа / выхода</dt>
                    <dd>
                        {decision.estimatedInputTokens?.toLocaleString('ru-RU') ?? '—'}
                        {' / '}
                        {decision.estimatedOutputTokens?.toLocaleString('ru-RU') ?? '—'}
                    </dd>
                    <small>
                        Исторические поля API и БД сохраняют технические имена *_tokens
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
                        Полнота стоимости: {decision.pricingComplete ? 'да' : 'нет'}
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
                        {monthlyCostStateLabel(decision.monthlyCostState)}
                        {' · '}
                        {budgetEnforcementLabel(decision.budgetEnforcement)}
                    </small>
                </div>

                <div>
                    <dt>Причина</dt>
                    <dd>{routeReasonLabel(decision.reason)}</dd>
                    <small>{decision.reason}</small>
                </div>
            </dl>

            <div className="models-route-accounting">
                <h3>Доказательства маршрутизации V48</h3>

                <div className="models-route-accounting__grid">
                    <div>
                        <span>Версия целостности</span>
                        <strong>
                            v{decision.decisionIntegrityVersion}
                        </strong>
                        <small>
                            Версия схемы доказательств и контрольного хэша,
                            а не версия AI-модели.
                        </small>
                    </div>

                    <div>
                        <span>Учёт входа</span>
                        <strong>
                            {decision.inputAccountingVersion
                                ?? 'Историческое решение V1/V2'}
                        </strong>
                        <small>
                            Версия алгоритма учёта входа.
                            Это не токенизатор провайдера.
                        </small>
                    </div>

                    <div>
                        <span>Резерв дополнительного входа</span>
                        <strong>
                            {decision.additionalInputUnitUpperBound ?? '—'}
                        </strong>
                        <small>
                            Верхняя граница дополнительного системного входа, контекста базы знаний
                            и описаний инструментов до обращения к провайдеру.
                        </small>
                    </div>
                </div>

                <code className="models-route-evidence__hash">
                    SHA-256: {decision.decisionSha256}
                </code>

                <p>
                    Для нового решения V48 ожидается версия целостности v3,
                    указанная версия учёта, неотрицательная граница входа
                    и 64-символьный SHA-256 в нижнем регистре. Исторические
                    решения V1/V2 не переписываются.
                </p>
            </div>
        </section>
    )
}

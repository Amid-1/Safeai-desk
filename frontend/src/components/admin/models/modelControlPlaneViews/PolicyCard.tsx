// frontend/src/components/admin/models/modelControlPlaneViews/PolicyCard.tsx
// Назначение: просмотр правил организации и выбор организации.

import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../../api/modelApi'
import type { OrganizationDirectoryItem } from '../../../../api/organizationApi'
import {formatUsd} from '../../../../utils/format'
import {
    StatusPill,
    InfoHint,
    policyAccessSummary,
    policyDataRequirements,
    policyTokenLimits,
} from './viewPresentation'

export function PolicyCard({
    policy,
    catalog,
    effectiveCatalog,
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
    effectiveCatalog: ModelCatalogEntry[]
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

    const undeclaredRuntimeModels = policy.enabled && policy.requireNoTraining
        ? effectiveCatalog.filter((entry) =>
            entry.provider === runtime.provider
            && entry.providerModelId === runtime.model
            && (entry.lifecycle === 'ACTIVE' || entry.lifecycle === 'DEPRECATED')
            && entry.trainingUseStatus === 'NOT_DECLARED',
        )
        : []

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

            {undeclaredRuntimeModels.length > 0 && (
                <div className="models-policy-form__warning" role="status">
                    <strong>Требование запрета обучения не подтверждено</strong>
                    <p>
                        В действующем каталоге для подключённой модели
                        {' '}{undeclaredRuntimeModels.map((entry) => entry.modelKey).join(', ')}
                        {' '}использование данных для обучения не заявлено (NOT_DECLARED).
                        Это несовместимо с включённым требованием «Не использовать данные
                        для обучения». Откройте «Изменить правила» и выполните
                        «Проверить правила», чтобы получить серверный результат.
                    </p>
                </div>
            )}

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

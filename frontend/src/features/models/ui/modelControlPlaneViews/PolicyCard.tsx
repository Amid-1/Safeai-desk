// frontend/src/features/models/ui/modelControlPlaneViews/PolicyCard.tsx
// Назначение: просмотр правил организации и выбор организации.

import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../../entities/model/api/modelApi'
import type { OrganizationDirectoryItem } from '../../../../entities/organization/api/organizationApi'
import {formatUsd} from '../../../../shared/lib/format'
import { StatusPill, InfoHint } from './viewPresentation'
import { policyAccessSummary, policyDataRequirements, policyTokenLimits } from './viewPresentationLabels'
import { useState } from 'react'
import { ModelIcon } from '../ModelIcon'

function OrganizationIdentifier({ value }: { value: string }) {
    const [message, setMessage] = useState('')
    async function copy() {
        try {
            await navigator.clipboard.writeText(value)
            setMessage('UUID скопирован')
        } catch {
            setMessage('Не удалось скопировать. Выделите UUID вручную.')
        }
    }
    return <div className="models-org-identifier"><code>{value}</code>
        <button type="button" className="models-copy-icon" aria-label="Копировать UUID организации" title="Копировать UUID организации" onClick={() => { void copy() }}><ModelIcon name="copy" /></button>
        {message && <small role="status">{message}</small>}
    </div>
}

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
    organizationSearchStatus,
    organizationSearchError,
    onOrganizationQueryChange,
    onOrganizationSearch,
    onOrganizationSelect,
    onEdit,
    editDisabled = false,
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
    organizationSearchStatus: 'idle' | 'pending' | 'success' | 'empty' | 'error'
    organizationSearchError: string
    onOrganizationQueryChange: (value: string) => void
    onOrganizationSearch: () => void | Promise<void>
    onOrganizationSelect: (organization: OrganizationDirectoryItem) => void
    onEdit: () => void
    editDisabled?: boolean
}) {
    const defaultEntry =
        policy.defaultModelKey
            ? effectiveCatalog.find(
                (entry) => entry.modelKey === policy.defaultModelKey,
            ) ?? null
            : null
    const savedDefaultEntry = policy.defaultModelKey
        ? catalog.find((entry) => entry.modelKey === policy.defaultModelKey)
        : null
    const constraintsActive = policy.configured && policy.enabled
    const restrictionState = (configured: boolean) => constraintsActive && configured ? 'configured' : 'neutral'

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

            {!policy.configured && <p className="models-policy-state-note models-policy-state-note--warning" role="note">
                Правила ещё не созданы. Сервер блокирует AI-запросы организации до настройки доступа.
            </p>}
            {policy.configured && !policy.enabled && <p className="models-policy-state-note" role="note">
                Правила отключены. Ниже показаны сохранённые параметры; дополнительные ограничения этой версии не применяются.
                Проверки подключения и каталога остаются обязательными.
            </p>}

            <div className="models-policy-organization-summary">
                <span className="models-inline-icon"><ModelIcon name="building" />Организация</span>
                <strong>
                    {organizationName ?? 'Название загружается…'}
                </strong>
                <OrganizationIdentifier key={organizationId} value={organizationId} />
            </div>

            {isSuperAdmin && (
                <form
                    className="models-org-picker"
                    onSubmit={(event) => {
                        event.preventDefault()
                        void onOrganizationSearch()
                    }}
                >
                    <label htmlFor="models-organization-search">
                        Проверить другую организацию
                    </label>
                    <p className="models-org-picker__description">
                            Найдите организацию по названию или UUID, затем выберите её
                            из результатов, чтобы посмотреть действующие правила доступа.
                    </p>
                    <div className="models-org-picker__input-row">
                            <input
                                id="models-organization-search"
                                type="search"
                                autoComplete="off"
                                aria-controls={organizationResults.length > 0 ? 'models-org-results' : undefined}
                                aria-describedby="models-org-search-status"
                                value={organizationQuery}
                                placeholder="Начните вводить название или UUID"
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

                    <div
                        id="models-org-search-status"
                        className={`models-org-picker__status${organizationSearchStatus === 'error' ? ' models-org-picker__status--error' : ''}`}
                        role="status"
                        aria-live="polite"
                    >
                        {organizationSearchStatus === 'pending' && 'Ищем организации…'}
                        {organizationSearchStatus === 'empty' && 'Ничего не найдено. Уточните название или проверьте UUID.'}
                        {organizationSearchStatus === 'error' && organizationSearchError}
                        {organizationSearchStatus === 'success' && `Найдено организаций: ${organizationResults.length}. Выберите нужную из списка.`}
                        {organizationSearchStatus === 'idle' && 'Поиск начнётся после ввода двух символов. Можно также нажать «Найти».'}
                    </div>
                    {organizationResults.length > 0 && (
                        <div className="models-org-results" id="models-org-results" aria-label="Результаты поиска организаций">
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
                    <dt><ModelIcon name="cpu" />Модель по умолчанию</dt>
                    <dd>
                        {defaultEntry?.displayName ?? policy.defaultModelKey
                            ?? runtime.model}
                    </dd>
                    {!policy.defaultModelKey && <small>Подключённая модель · {runtime.provider}</small>}
                    {defaultEntry && (
                        <small>
                            {defaultEntry.provider}
                            {' / '}
                            {defaultEntry.providerModelId}
                        </small>
                    )}
                    {policy.defaultModelKey && !defaultEntry && (
                        <small>{savedDefaultEntry ? 'Нет действующей версии' : 'Модель отсутствует в каталоге'}</small>
                    )}
                </div>

                <div data-restriction={restrictionState(Boolean(policy.allowModelKeys.length || policy.denyModelKeys.length))}>
                    <dt>
                        <span className="models-label-row">
                            <ModelIcon name="list" />Списки доступа
                            <InfoHint text="Если список разрешённых пуст, доступны все модели, которые не запрещены явно и проходят остальные проверки правил и настроек подключения." />
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

                <div data-restriction={restrictionState(policy.maxRequestCostUsd !== null)}>
                    <dt><ModelIcon name="wallet" />Стоимость запроса</dt>
                    <dd>
                        {policy.maxRequestCostUsd === null
                            ? 'Без лимита'
                            : formatUsd(policy.maxRequestCostUsd)}
                    </dd>
                </div>

                <div data-restriction={restrictionState(policy.monthlyBudgetUsd !== null)}>
                    <dt><ModelIcon name="budget" />Бюджет на месяц</dt>
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

                <div data-restriction={restrictionState(policy.requireCompletePricing || policy.requireNoTraining || policy.requireZeroDataRetention)}>
                    <dt><ModelIcon name="shield" />Требования безопасности и стоимости</dt>
                    <dd>
                        {policyDataRequirements(policy)}
                    </dd>
                </div>

                <div data-restriction={restrictionState(policy.maxInputTokens !== null || policy.maxOutputTokens !== null)}>
                    <dt><ModelIcon name="gauge" />Лимиты токенов</dt>
                    <dd>
                        {policyTokenLimits(policy)}
                    </dd>
                    <small>
                        Подключённая модель: {' '}
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
                        {' '}не указано, используются ли данные для обучения.
                        Это несовместимо с включённым требованием «Не использовать данные
                        для обучения». Откройте «Изменить правила» и выполните
                        «Проверить правила», чтобы получить серверный результат.
                    </p>
                </div>
            )}

            <div className="models-policy-card__footer">
                <p className="models-policy-card__note">
                    Правила ограничивают использование моделей этой
                    организацией. Подключение модели к провайдеру
                    настраивается отдельно.
                </p>

                <button
                    type="button"
                    className="btn-primary models-policy-action"
                    disabled={editDisabled}
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

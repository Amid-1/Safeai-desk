// frontend/src/features/models/ui/modelControlPlaneViews/PolicyCard.tsx
// Назначение: просмотр правил организации и выбор организации.

import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../../entities/model/api/modelApi'
import type { OrganizationDirectoryItem } from '../../../../entities/organization/api/organizationApi'
import {formatUsd} from '../../../../shared/lib/format'
import { StatusPill } from './viewPresentation'
import { policyAccessSummary, policyDataRequirements, policyTokenLimits } from './viewPresentationLabels'
import { useState } from 'react'
import { SearchableSelect } from '../../../../shared/ui/SearchableSelect'
import type { ReactNode } from 'react'
import { ModelIcon } from '../ModelIcon'
import type { ModelIconName } from '../ModelIcon'
import type { PolicyEditorTarget } from '../modelPolicyModal/policyFormTabsConfig'

function formatRub(value: string) {
    const [whole, fraction = ''] = value.split('.')
    const decimals = fraction.replace(/0+$/, '')
    return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, '\u00a0')}${decimals ? ',' + decimals : ''} ₽`
}

function PolicyTile({icon, title, value, detail, target, restriction, disabled, onEdit}: {
    icon: ModelIconName; title: string; value: ReactNode; detail?: ReactNode
    target: PolicyEditorTarget; restriction?: string; disabled: boolean
    onEdit: (target: PolicyEditorTarget) => void
}) {
    return <div className={disabled ? undefined : 'models-policy-action-tile'} data-restriction={restriction}>
        <dt><ModelIcon name={icon} />{title}
            {!disabled && <span className="models-runtime-tile-indicator"><ModelIcon name="arrow" /></span>}
        </dt>
        <dd>{value}</dd>
        {detail && <small>{detail}</small>}
        <small><button type="button" className="models-policy-tile-action" disabled={disabled}
            aria-label={`Настроить: ${title}`} aria-haspopup="dialog"
            onClick={() => onEdit(target)}>Настроить</button></small>
    </div>
}

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
    onEdit: (target?: PolicyEditorTarget) => void
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

    const undeclaredRuntimeModels = constraintsActive && policy.requireNoTraining
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
                <details><summary>Технические сведения</summary>
                    <OrganizationIdentifier key={organizationId} value={organizationId} />
                </details>
            </div>

            {isSuperAdmin && <div className="models-org-picker">
                <SearchableSelect id="models-organization-search" label="Управляемая организация"
                    value={organizationId} selectedLabel={organizationName ?? 'Название загружается…'}
                    options={organizationResults.map(org => ({
                        value: org.id, label: org.name + (org.enabled ? '' : ' (отключена)'),
                    }))}
                    placeholder="Выберите организацию" searchPlaceholder="Название или UUID"
                    emptyMessage={organizationQuery.trim().length < 2
                        ? 'Введите не менее двух символов.' : 'Ничего не найдено. Уточните название или идентификатор.'}
                    allowClear={false} disabled={editDisabled} filterLocally={false}
                    loading={organizationSearchPending} error={organizationSearchError}
                    onQueryChange={onOrganizationQueryChange}
                    onChange={id => {
                        const organization = organizationResults.find(row => row.id === id)
                        if (organization) onOrganizationSelect(organization)
                    }} />
                {organizationSearchStatus === 'error' && <div role="status">
                    {organizationSearchError}{' '}
                    <button type="button" className="secondary-button" onClick={() => void onOrganizationSearch()}>Повторить поиск</button>
                </div>}
                <a className="admin-related-link" href={`/admin/organizations?organizationId=${encodeURIComponent(organizationId)}`}>Карточка организации</a>
            </div>}

            <dl className="models-kv-grid models-policy-tiles">
                <PolicyTile icon="cpu" title="Модель по умолчанию" target="default"
                    value={defaultEntry?.displayName ?? policy.defaultModelKey ?? 'Автоматически'}
                    detail={!policy.defaultModelKey ? `Текущая модель: ${runtime.model} · ${runtime.provider}`
                        : defaultEntry ? `${defaultEntry.provider} / ${defaultEntry.providerModelId}${defaultEntry.lifecycle === 'DISABLED' || defaultEntry.lifecycle === 'RETIRED' ? ' · недоступна в каталоге' : defaultEntry.provider !== runtime.provider || defaultEntry.providerModelId !== runtime.model ? ' · не совпадает с подключением' : ''}`
                        : savedDefaultEntry ? 'Нет действующей версии' : 'Модель отсутствует в каталоге'}
                    disabled={editDisabled} onEdit={onEdit} />
                <PolicyTile icon="list" title="Разрешения и запреты" target="access"
                    value={policyAccessSummary(policy)}
                    detail={policy.allowModelKeys.length === 0 ? 'Без явного списка разрешений; остальные проверки действуют' : 'Заданы разрешённые ключи каталога'}
                    restriction={restrictionState(Boolean(policy.allowModelKeys.length || policy.denyModelKeys.length))}
                    disabled={editDisabled} onEdit={onEdit} />
                <PolicyTile icon="gauge" title="Лимиты токенов" target="limits"
                    value={policyTokenLimits(policy)}
                    detail={`Лимиты подключения (вход / выход): ${runtime.maxInputTokens.toLocaleString('ru-RU')} / ${runtime.maxOutputTokens.toLocaleString('ru-RU')}`}
                    restriction={restrictionState(policy.maxInputTokens !== null || policy.maxOutputTokens !== null)}
                    disabled={editDisabled} onEdit={onEdit} />
                <PolicyTile icon="wallet" title="Лимит стоимости запроса" target="requestCost"
                    value={policy.rubBudget?.maxRequestCostRub != null ? formatRub(policy.rubBudget.maxRequestCostRub) : policy.maxRequestCostUsd === null ? 'Без лимита' : formatUsd(policy.maxRequestCostUsd)}
                    detail={policy.rubBudget?.maxRequestCostRub != null ? `${policy.maxRequestCostUsd} USD · фиксированный курс` : 'Максимальная расчётная стоимость одного запроса'}
                    restriction={restrictionState(policy.maxRequestCostUsd !== null)}
                    disabled={editDisabled} onEdit={onEdit} />
                <PolicyTile icon="budget" title="Месячный бюджет" target="monthlyBudget"
                    value={policy.rubBudget?.monthlyBudgetRub != null ? formatRub(policy.rubBudget.monthlyBudgetRub) : policy.monthlyBudgetUsd === null ? 'Не задан' : formatUsd(policy.monthlyBudgetUsd)}
                    detail={policy.monthlyBudgetUsd === null ? 'Контроль бюджета выключен'
                        : <>{policy.rubBudget?.monthlyBudgetRub != null && <>{policy.monthlyBudgetUsd} USD · фиксированный курс<br/></>}{policy.budgetEnforcement === 'HARD' ? 'Жёсткий контроль: блокировка превышения' : 'Мягкий контроль: фиксация превышения'}</>}
                    restriction={restrictionState(policy.monthlyBudgetUsd !== null)}
                    disabled={editDisabled} onEdit={onEdit} />
                <PolicyTile icon="shield" title="Требования к данным и стоимости" target="data"
                    value={policyDataRequirements(policy)}
                    detail="Стоимость, использование для обучения и хранение данных"
                    restriction={restrictionState(policy.requireCompletePricing || policy.requireNoTraining || policy.requireZeroDataRetention)}
                    disabled={editDisabled} onEdit={onEdit} />
            </dl>

            {constraintsActive && ((policy.requireNoTraining && runtime.dataAssurance?.noTraining !== true)
                || (policy.requireZeroDataRetention && runtime.dataAssurance?.zeroDataRetention !== true)) && (
                <div className="models-policy-form__warning" role="status">
                    <strong>Условия серверного подключения не подтверждены</strong>
                    <p>Требования к обучению или хранению данных не подтверждены для текущего подключения. Одной записи в каталоге недостаточно.</p>
                </div>
            )}
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

                <button type="button" className="secondary-button" disabled={editDisabled}
                    onClick={() => onEdit('preview')}>Открыть проверку правил</button>
                <button
                    type="button"
                    className="btn-primary models-policy-action"
                    disabled={editDisabled}
                    onClick={() => onEdit()}
                >
                    {policy.configured
                        ? 'Изменить правила'
                        : 'Настроить правила'}
                </button>
            </div>
        </section>
    )
}

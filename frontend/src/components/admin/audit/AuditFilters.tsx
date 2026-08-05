// ============================================================
// frontend/src/components/admin/audit/AuditFilters.tsx
// ============================================================
import type {
    Dispatch,
    SetStateAction,
} from 'react'

import type {
    AuditActorDirectoryItem,
    AuditTargetOrganizationDirectoryItem,
} from '../../../api/adminApi'

import {
    getAuditEventTypeLabel,
} from '../../../constants/auditEvents'

import type {
    AuditDraftFilter,
    DatePreset,
} from './types'

type AuditFiltersProps = {
    draftFilter: AuditDraftFilter

    eventTypes: string[]

    organizations:
        AuditTargetOrganizationDirectoryItem[]

    actors:
        AuditActorDirectoryItem[]

    superAdmin: boolean
    loading: boolean
    directoriesLoading: boolean

    eventTypesError: string
    organizationsError: string
    actorsError: string
    filterError: string
    filtersDirty: boolean

    onFilterChange:
        Dispatch<
            SetStateAction<
                AuditDraftFilter
            >
        >

    onActorSearch:
        (query: string) => Promise<void>

    onOrganizationSearch:
        (query: string) => Promise<void>

    onDatePreset:
        (preset: DatePreset) => void

    onApply: () => void
    onReset: () => void
}

function AuditFilters({
    draftFilter,

    eventTypes,
    organizations,
    actors,

    superAdmin,
    loading,
    directoriesLoading,

    eventTypesError,
    organizationsError,
    actorsError,
    filterError,
    filtersDirty,

    onFilterChange,
    onActorSearch,
    onOrganizationSearch,
    onDatePreset,
    onApply,
    onReset,
}: AuditFiltersProps) {
    const visibleEventTypes =
        buildVisibleEventTypes(
            eventTypes,
            draftFilter.eventType,
        )

    const visibleActors =
        buildVisibleActors(
            actors,
            draftFilter.actorUserId,
        )

    const visibleOrganizations =
        buildVisibleOrganizations(
            organizations,
            draftFilter
                .targetOrganizationId,
        )

    return (
        <div className="card form-card">
            <div className="form">
                <label>
                    Тип события

                    <select
                        value={
                            draftFilter
                                .eventType
                        }
                        disabled={
                            loading
                        }
                        onChange={(event) => {
                            const eventType =
                                event.target
                                    .value

                            onFilterChange(
                                (current) => ({
                                    ...current,
                                    eventType,
                                }),
                            )
                        }}
                    >
                        <option value="">
                            Все события
                        </option>

                        {visibleEventTypes.map(
                            (eventType) => (
                                <option
                                    key={
                                        eventType
                                    }
                                    value={
                                        eventType
                                    }
                                >
                                    {
                                        getAuditEventTypeLabel(
                                            eventType,
                                        )
                                    }
                                </option>
                            ),
                        )}
                    </select>
                </label>

                {eventTypesError && (
                    <DirectoryError
                        message={
                            eventTypesError
                        }
                    />
                )}

                {superAdmin && (
                    <>
                        <label>
                            Поиск целевой
                            организации

                            <input
                                type="search"
                                disabled={
                                    loading
                                }
                                placeholder={
                                    'Название или ID'
                                }
                                onChange={(event) => {
                                    void onOrganizationSearch(
                                        event.target
                                            .value,
                                    )
                                }}
                            />
                        </label>

                        <label>
                            Целевая
                            организация

                            <select
                                value={
                                    draftFilter
                                        .targetOrganizationId
                                }
                                disabled={
                                    loading
                                    || directoriesLoading
                                }
                                onChange={(event) => {
                                    const targetOrganizationId =
                                        event.target
                                            .value

                                    onFilterChange(
                                        (current) => ({
                                            ...current,
                                            targetOrganizationId,
                                        }),
                                    )
                                }}
                            >
                                <option value="">
                                    Все организации
                                </option>

                                {
                                    visibleOrganizations
                                        .map(
                                            (
                                                organization,
                                            ) => (
                                                <option
                                                    key={
                                                        organization
                                                            .targetOrganizationId
                                                    }
                                                    value={
                                                        organization
                                                            .targetOrganizationId
                                                    }
                                                >
                                                    {
                                                        formatOrganizationOption(
                                                            organization,
                                                        )
                                                    }
                                                </option>
                                            ),
                                        )
                                }
                            </select>
                        </label>

                        {organizationsError && (
                            <DirectoryError
                                message={
                                    organizationsError
                                }
                            />
                        )}
                    </>
                )}

                <label>
                    Поиск инициатора

                    <input
                        type="search"
                        disabled={
                            loading
                        }
                        placeholder={
                            'Email или имя'
                        }
                        onChange={(event) => {
                            void onActorSearch(
                                event.target
                                    .value,
                            )
                        }}
                    />
                </label>

                <label>
                    Инициатор

                    <select
                        value={
                            draftFilter
                                .actorUserId
                        }
                        disabled={
                            loading
                            || directoriesLoading
                        }
                        onChange={(event) => {
                            const actorUserId =
                                event.target
                                    .value

                            onFilterChange(
                                (current) => ({
                                    ...current,
                                    actorUserId,
                                }),
                            )
                        }}
                    >
                        <option value="">
                            Все инициаторы
                        </option>

                        {visibleActors.map(
                            (actor) => (
                                <option
                                    key={
                                        actor
                                            .actorUserId
                                    }
                                    value={
                                        actor
                                            .actorUserId
                                    }
                                >
                                    {
                                        formatActorOption(
                                            actor,
                                        )
                                    }
                                </option>
                            ),
                        )}
                    </select>
                </label>

                <label>
                    Email инициатора
                    или префикс

                    <input
                        type="search"
                        value={
                            draftFilter
                                .actorEmail
                        }
                        disabled={
                            loading
                        }
                        maxLength={320}
                        autoComplete="off"
                        placeholder={
                            'admin@safeai.test'
                        }
                        onChange={(event) => {
                            const actorEmail =
                                event.target
                                    .value

                            onFilterChange(
                                (current) => ({
                                    ...current,
                                    actorEmail,
                                }),
                            )
                        }}
                    />
                </label>

                {actorsError && (
                    <DirectoryError
                        message={
                            actorsError
                        }
                    />
                )}

                <div className="audit-date-presets">
                    <span className="audit-date-presets__label">
                        Быстрый период
                    </span>

                    <div className="audit-date-presets__actions">
                        <PresetButton
                            label="Сегодня"
                            disabled={
                                loading
                            }
                            onClick={() => {
                                onDatePreset(
                                    'today',
                                )
                            }}
                        />

                        <PresetButton
                            label="Вчера"
                            disabled={
                                loading
                            }
                            onClick={() => {
                                onDatePreset(
                                    'yesterday',
                                )
                            }}
                        />

                        <PresetButton
                            label="7 дней"
                            disabled={
                                loading
                            }
                            onClick={() => {
                                onDatePreset(
                                    'last7Days',
                                )
                            }}
                        />

                        <PresetButton
                            label="30 дней"
                            disabled={
                                loading
                            }
                            onClick={() => {
                                onDatePreset(
                                    'last30Days',
                                )
                            }}
                        />

                        <PresetButton
                            label="365 дней"
                            disabled={
                                loading
                            }
                            onClick={() => {
                                onDatePreset(
                                    'last365Days',
                                )
                            }}
                        />
                    </div>
                </div>

                <label>
                    Дата с

                    <input
                        type="date"
                        value={
                            draftFilter
                                .dateFrom
                        }
                        max={
                            draftFilter
                                .dateTo
                            || undefined
                        }
                        disabled={
                            loading
                        }
                        onChange={(event) => {
                            const dateFrom =
                                event.target
                                    .value

                            onFilterChange(
                                (current) => ({
                                    ...current,
                                    dateFrom,
                                }),
                            )
                        }}
                    />
                </label>

                <label>
                    Дата по

                    <input
                        type="date"
                        value={
                            draftFilter
                                .dateTo
                        }
                        min={
                            draftFilter
                                .dateFrom
                            || undefined
                        }
                        disabled={
                            loading
                        }
                        onChange={(event) => {
                            const dateTo =
                                event.target
                                    .value

                            onFilterChange(
                                (current) => ({
                                    ...current,
                                    dateTo,
                                }),
                            )
                        }}
                    />
                </label>

                {filtersDirty && (
                    <div
                        className="audit-filter-notice"
                        role="status"
                    >
                        Фильтры изменены.
                        Нажмите
                        «Применить фильтры».
                    </div>
                )}

                {filterError && (
                    <div
                        className="error"
                        role="alert"
                    >
                        {filterError}
                    </div>
                )}

                <div className="filter-actions">
                    <button
                        type="button"
                        disabled={
                            loading
                            || !filtersDirty
                        }
                        onClick={
                            onApply
                        }
                    >
                        Применить фильтры
                    </button>

                    <button
                        type="button"
                        className="secondary-button"
                        disabled={
                            loading
                        }
                        onClick={
                            onReset
                        }
                    >
                        Сбросить фильтры
                    </button>
                </div>
            </div>
        </div>
    )
}

type PresetButtonProps = {
    label: string
    disabled: boolean
    onClick: () => void
}

function PresetButton({
    label,
    disabled,
    onClick,
}: PresetButtonProps) {
    return (
        <button
            type="button"
            className="secondary-button"
            disabled={disabled}
            onClick={onClick}
        >
            {label}
        </button>
    )
}

type DirectoryErrorProps = {
    message: string
}

function DirectoryError({
    message,
}: DirectoryErrorProps) {
    return (
        <div
            className="error"
            role="alert"
        >
            {message}
        </div>
    )
}

function buildVisibleEventTypes(
    eventTypes: readonly string[],
    selectedEventType: string,
): string[] {
    const result =
        new Set<string>(
            eventTypes,
        )

    if (selectedEventType) {
        result.add(
            selectedEventType,
        )
    }

    return [...result].sort(
        (first, second) =>
            getAuditEventTypeLabel(
                first,
            ).localeCompare(
                getAuditEventTypeLabel(
                    second,
                ),
                'ru',
                {
                    sensitivity:
                        'base',
                },
            ),
    )
}

function buildVisibleActors(
    actors:
        readonly AuditActorDirectoryItem[],
    selectedActorUserId: string,
): Array<
    AuditActorDirectoryItem & {
        actorUserId: string
    }
> {
    const byId =
        new Map<
            string,
            AuditActorDirectoryItem & {
                actorUserId: string
            }
        >()

    for (const actor of actors) {
        if (!actor.actorUserId) {
            continue
        }

        byId.set(
            actor.actorUserId,
            {
                ...actor,
                actorUserId:
                    actor.actorUserId,
            },
        )
    }

    if (
        selectedActorUserId
        && !byId.has(
            selectedActorUserId,
        )
    ) {
        byId.set(
            selectedActorUserId,
            {
                actorUserId:
                    selectedActorUserId,
                actorOrganizationId:
                    null,
                actorEmail:
                    null,
                actorDisplayName:
                    null,
            },
        )
    }

    return [...byId.values()].sort(
        (first, second) =>
            formatActorOption(
                first,
            ).localeCompare(
                formatActorOption(
                    second,
                ),
                'ru',
                {
                    sensitivity:
                        'base',
                },
            ),
    )
}

function buildVisibleOrganizations(
    organizations:
        readonly AuditTargetOrganizationDirectoryItem[],
    selectedOrganizationId: string,
): AuditTargetOrganizationDirectoryItem[] {
    const byId =
        new Map<
            string,
            AuditTargetOrganizationDirectoryItem
        >()

    for (
        const organization
        of organizations
    ) {
        byId.set(
            organization
                .targetOrganizationId,
            organization,
        )
    }

    if (
        selectedOrganizationId
        && !byId.has(
            selectedOrganizationId,
        )
    ) {
        byId.set(
            selectedOrganizationId,
            {
                targetOrganizationId:
                    selectedOrganizationId,
                targetOrganizationName:
                    null,
            },
        )
    }

    return [...byId.values()].sort(
        (first, second) =>
            formatOrganizationOption(
                first,
            ).localeCompare(
                formatOrganizationOption(
                    second,
                ),
                'ru',
                {
                    sensitivity:
                        'base',
                },
            ),
    )
}

function formatActorOption(
    actor: AuditActorDirectoryItem,
): string {
    const email =
        normalizeOptionalText(
            actor.actorEmail,
        )

    const displayName =
        normalizeOptionalText(
            actor.actorDisplayName,
        )

    const actorUserId =
        actor.actorUserId
        ?? 'unknown-user'

    if (email && displayName) {
        return (
            `${email} — ${displayName}`
            + ` (${actorUserId})`
        )
    }

    return (
        email
        ?? displayName
        ?? actorUserId
    )
}

function formatOrganizationOption(
    organization:
        AuditTargetOrganizationDirectoryItem,
): string {
    const name =
        normalizeOptionalText(
            organization
                .targetOrganizationName,
        )

    if (!name) {
        return organization
            .targetOrganizationId
    }

    return (
        `${name} — `
        + organization
            .targetOrganizationId
    )
}

function normalizeOptionalText(
    value: string | null,
): string | null {
    if (value === null) {
        return null
    }

    const normalized =
        value.trim()

    return normalized || null
}

export default AuditFilters
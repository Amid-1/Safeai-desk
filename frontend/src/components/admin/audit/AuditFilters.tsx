// ============================================================
// frontend/src/components/admin/audit/AuditFilters.tsx
// ============================================================
import {
    useEffect,
    useRef,
    useState,
} from 'react'
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

const DIRECTORY_SEARCH_DELAY_MS = 300

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

    onActorSearch: (
        query: string,
        targetOrganizationId?: string,
    ) => Promise<void>

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
    const [
        organizationQuery,
        setOrganizationQuery,
    ] = useState('')

    const [
        actorQuery,
        setActorQuery,
    ] = useState('')

    const organizationSearchTimerRef =
        useRef<number | null>(null)

    const actorSearchTimerRef =
        useRef<number | null>(null)

    useEffect(() => {
        return () => {
            clearSearchTimer(
                organizationSearchTimerRef,
            )

            clearSearchTimer(
                actorSearchTimerRef,
            )
        }
    }, [])

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

    function scheduleOrganizationSearch(
        query: string,
    ) {
        setOrganizationQuery(query)

        clearSearchTimer(
            organizationSearchTimerRef,
        )

        organizationSearchTimerRef.current =
            window.setTimeout(
                () => {
                    void onOrganizationSearch(
                        query,
                    )
                },
                DIRECTORY_SEARCH_DELAY_MS,
            )
    }

    function scheduleActorSearch(
        query: string,
    ) {
        setActorQuery(query)

        clearSearchTimer(
            actorSearchTimerRef,
        )

        /*
         * Инициатор и целевая организация являются
         * независимыми измерениями аудита.
         *
         * Поэтому targetOrganizationId намеренно
         * не передаётся в справочный поиск инициатора.
         * Фильтрация событий выполняется отдельно
         * при нажатии «Применить фильтры».
         */
        actorSearchTimerRef.current =
            window.setTimeout(
                () => {
                    void onActorSearch(query)
                },
                DIRECTORY_SEARCH_DELAY_MS,
            )
    }

    return (
        <section
            className={
                'card form-card '
                + 'audit-filters'
            }
            aria-labelledby={
                'audit-filters-title'
            }
        >
            <h2 id="audit-filters-title">
                Найти события
            </h2>
            <p className="muted audit-filters__subtitle">
                Уточните тип события, участника или период.
            </p>

            <div className="form">
                <label className="audit-filter-field audit-filter-field--event-type">
                    Тип события

                    <select
                        value={
                            draftFilter.eventType
                        }
                        disabled={loading}
                        onChange={(event) => {
                            const eventType =
                                event.target.value

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
                                    key={eventType}
                                    value={eventType}
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

                <DirectoryWarning
                    message={
                        eventTypesError
                    }
                />

                {superAdmin && (
                    <>
                        <label className="audit-filter-field audit-filter-field--organization-search">
                            Поиск целевой
                            организации

                            <input
                                type="search"
                                value={
                                    organizationQuery
                                }
                                maxLength={255}
                                disabled={loading}
                                autoComplete="off"
                                placeholder={
                                    'Название или UUID'
                                }
                                onChange={(event) =>
                                    scheduleOrganizationSearch(
                                        event.target.value,
                                    )
                                }
                            />

                            <small className="muted">
                                Поиск обновляет список.
                                Фильтр применяется после
                                выбора организации.
                            </small>
                        </label>

                        <label className="audit-filter-field audit-filter-field--organization">
                            Целевая организация

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
                                        event.target.value

                                    onFilterChange(
                                        (current) => ({
                                            ...current,
                                            targetOrganizationId,
                                        }),
                                    )
                                }}
                            >
                                <option value="">
                                    Все целевые организации
                                </option>

                                {visibleOrganizations.map(
                                    (organization) => (
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
                                )}
                            </select>
                        </label>

                        <DirectoryWarning
                            message={
                                organizationsError
                            }
                        />
                    </>
                )}

                <label className="audit-filter-field audit-filter-field--actor-search">
                    Найти пользователя

                    <input
                        type="search"
                        value={actorQuery}
                        maxLength={320}
                        disabled={loading}
                        autoComplete="off"
                        placeholder={
                            'Email, имя или UUID'
                        }
                        onChange={(event) =>
                            scheduleActorSearch(
                                event.target.value,
                            )
                        }
                    />

                </label>

                <label className="audit-filter-field audit-filter-field--actor">
                    Инициатор

                    <select
                        value={
                            draftFilter.actorUserId
                        }
                        disabled={
                            loading
                            || directoriesLoading
                        }
                        onChange={(event) => {
                            const actorUserId =
                                event.target.value

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
                                        actor.actorUserId
                                    }
                                    value={
                                        actor.actorUserId
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

                <label className="audit-filter-field audit-filter-field--email">
                    Email пользователя

                    <input
                        type="search"
                        value={
                            draftFilter.actorEmail
                        }
                        maxLength={320}
                        disabled={loading}
                        autoComplete="off"
                        placeholder={
                            'admin@safeai.test'
                        }
                        onChange={(event) => {
                            const actorEmail =
                                event.target.value

                            onFilterChange(
                                (current) => ({
                                    ...current,
                                    actorEmail,
                                }),
                            )
                        }}
                    />
                </label>

                <DirectoryWarning
                    message={
                        actorsError
                    }
                />

                <div className="audit-date-presets">
                    <span
                        className={
                            'audit-date-presets__label'
                        }
                    >
                        Период
                    </span>

                    <div
                        className={
                            'audit-date-presets__actions'
                        }
                    >
                        <PresetButton
                            label="Сегодня"
                            disabled={loading}
                            onClick={() =>
                                onDatePreset(
                                    'today',
                                )
                            }
                        />

                        <PresetButton
                            label="Вчера"
                            disabled={loading}
                            onClick={() =>
                                onDatePreset(
                                    'yesterday',
                                )
                            }
                        />

                        <PresetButton
                            label="7 дней"
                            disabled={loading}
                            onClick={() =>
                                onDatePreset(
                                    'last7Days',
                                )
                            }
                        />

                        <PresetButton
                            label="30 дней"
                            disabled={loading}
                            onClick={() =>
                                onDatePreset(
                                    'last30Days',
                                )
                            }
                        />

                        <PresetButton
                            label="365 дней"
                            disabled={loading}
                            onClick={() =>
                                onDatePreset(
                                    'last365Days',
                                )
                            }
                        />
                    </div>
                </div>

                <label className="audit-filter-field audit-filter-field--date">
                    Дата с

                    <input
                        type="date"
                        value={
                            draftFilter.dateFrom
                        }
                        max={
                            draftFilter.dateTo
                            || undefined
                        }
                        required
                        disabled={loading}
                        onChange={(event) => {
                            const dateFrom =
                                event.target.value

                            onFilterChange(
                                (current) => ({
                                    ...current,
                                    dateFrom,
                                }),
                            )
                        }}
                    />
                </label>

                <label className="audit-filter-field audit-filter-field--date">
                    Дата по

                    <input
                        type="date"
                        value={
                            draftFilter.dateTo
                        }
                        min={
                            draftFilter.dateFrom
                            || undefined
                        }
                        required
                        disabled={loading}
                        onChange={(event) => {
                            const dateTo =
                                event.target.value

                            onFilterChange(
                                (current) => ({
                                    ...current,
                                    dateTo,
                                }),
                            )
                        }}
                    />
                </label>

                <small className="muted audit-date-help">
                    Обе даты включены в отчёт.
                </small>

                {filtersDirty && (
                    <div
                        className={
                            'audit-filter-notice'
                        }
                        role="status"
                        aria-live="polite"
                    >
                        Есть несохранённые изменения фильтров.
                    </div>
                )}

                {directoriesLoading && (
                    <div
                        className="muted audit-directory-loading"
                        role="status"
                        aria-live="polite"
                    >
                        Загружаются справочники
                        аудита…
                    </div>
                )}

                {filterError && (
                    <div
                        className="error"
                        role="alert"
                        aria-live="assertive"
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
                        onClick={onApply}
                    >
                        Показать события
                    </button>

                    <button
                        type="button"
                        className={
                            'secondary-button'
                        }
                        disabled={loading}
                        onClick={onReset}
                    >
                        Последние 30 дней
                    </button>
                </div>
            </div>
        </section>
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

function DirectoryWarning({
    message,
}: {
    message: string
}) {
    return message ? (
        <div
            className={
                'audit-directory-warning'
            }
            role="status"
            aria-live="polite"
        >
            {message}
        </div>
    ) : null
}

function buildVisibleEventTypes(
    eventTypes: readonly string[],
    selectedEventType: string,
): string[] {
    const uniqueEventTypes =
        new Set<string>(
            eventTypes,
        )

    if (selectedEventType) {
        uniqueEventTypes.add(
            selectedEventType,
        )
    }

    return [...uniqueEventTypes].sort(
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

    const identity =
        email && displayName
            ? `${email} — ${displayName}`
            : (
                email
                ?? displayName
                ?? 'Инициатор без имени'
            )

    return (
        `${identity} (${actorUserId})`
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
        return (
            'Название недоступно — '
            + organization
                .targetOrganizationId
        )
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

type SearchTimerRef = {
    current: number | null
}

function clearSearchTimer(
    timerRef: SearchTimerRef,
) {
    if (timerRef.current === null) {
        return
    }

    window.clearTimeout(
        timerRef.current,
    )

    timerRef.current = null
}

export default AuditFilters

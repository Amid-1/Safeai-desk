// ============================================================
// frontend/src/components/admin/audit/AuditFilters.tsx
// ============================================================
import type {
    Organization,
} from '../../../api/organizationApi'

import type {
    User,
} from '../../../api/userApi'

import {
    AUDIT_EVENT_TYPES,
    getAuditEventTypeLabel,
} from '../../../constants/auditEvents'

import type {
    AuditDraftFilter,
    DatePreset,
} from './types'

type AuditFiltersProps = {
    draftFilter: AuditDraftFilter
    organizations: Organization[]
    visibleUsers: User[]
    organizationNameById: Map<string, string>
    superAdmin: boolean
    loading: boolean
    directoriesLoading: boolean
    directoriesError: string
    filterError: string
    filtersDirty: boolean
    onFilterChange: (
        updater: (
            current: AuditDraftFilter,
        ) => AuditDraftFilter,
    ) => void
    onOrganizationChange: (
        organizationId: string,
    ) => void
    onDatePreset: (preset: DatePreset) => void
    onApply: () => void
    onReset: () => void
}

function AuditFilters({
                          draftFilter,
                          organizations,
                          visibleUsers,
                          organizationNameById,
                          superAdmin,
                          loading,
                          directoriesLoading,
                          directoriesError,
                          filterError,
                          filtersDirty,
                          onFilterChange,
                          onOrganizationChange,
                          onDatePreset,
                          onApply,
                          onReset,
                      }: AuditFiltersProps) {
    return (
        <div className="card form-card">
            <div className="form">
                <label>
                    Тип события

                    <select
                        value={draftFilter.eventType}
                        onChange={(event) =>
                            onFilterChange((current) => ({
                                ...current,
                                eventType:
                                event.target.value,
                            }))
                        }
                    >
                        <option value="">
                            Все события
                        </option>

                        {AUDIT_EVENT_TYPES.map(
                            (eventType) => (
                                <option
                                    key={eventType}
                                    value={eventType}
                                >
                                    {getAuditEventTypeLabel(
                                        eventType,
                                    )}
                                </option>
                            ),
                        )}
                    </select>
                </label>

                {superAdmin && (
                    <label>
                        Организация

                        <select
                            value={
                                draftFilter.organizationId
                            }
                            disabled={directoriesLoading}
                            onChange={(event) =>
                                onOrganizationChange(
                                    event.target.value,
                                )
                            }
                        >
                            <option value="">
                                {directoriesLoading
                                    ? 'Загрузка организаций...'
                                    : 'Все организации'}
                            </option>

                            {organizations.map(
                                (organization) => (
                                    <option
                                        key={organization.id}
                                        value={organization.id}
                                    >
                                        {organization.name}
                                    </option>
                                ),
                            )}
                        </select>
                    </label>
                )}

                <label>
                    Пользователь

                    <select
                        value={draftFilter.userId}
                        disabled={directoriesLoading}
                        onChange={(event) =>
                            onFilterChange((current) => ({
                                ...current,
                                userId:
                                event.target.value,
                            }))
                        }
                    >
                        <option value="">
                            {directoriesLoading
                                ? 'Загрузка пользователей...'
                                : 'Все пользователи'}
                        </option>

                        {visibleUsers.map((user) => (
                            <option
                                key={user.id}
                                value={user.id}
                            >
                                {formatUserOption(
                                    user,
                                    superAdmin,
                                    organizationNameById,
                                )}
                            </option>
                        ))}
                    </select>
                </label>

                <div className="audit-date-presets">
                    <span className="audit-date-presets__label">
                        Быстрый период
                    </span>

                    <div className="audit-date-presets__actions">
                        <PresetButton
                            label="Сегодня"
                            onClick={() =>
                                onDatePreset('today')
                            }
                        />

                        <PresetButton
                            label="Вчера"
                            onClick={() =>
                                onDatePreset('yesterday')
                            }
                        />

                        <PresetButton
                            label="7 дней"
                            onClick={() =>
                                onDatePreset('last7Days')
                            }
                        />

                        <PresetButton
                            label="30 дней"
                            onClick={() =>
                                onDatePreset('last30Days')
                            }
                        />

                        <PresetButton
                            label="Всё время"
                            onClick={() =>
                                onDatePreset('all')
                            }
                        />
                    </div>
                </div>

                <label>
                    Дата с

                    <input
                        type="date"
                        value={draftFilter.dateFrom}
                        max={
                            draftFilter.dateTo ||
                            undefined
                        }
                        onChange={(event) =>
                            onFilterChange((current) => ({
                                ...current,
                                dateFrom:
                                event.target.value,
                            }))
                        }
                    />
                </label>

                <label>
                    Дата по

                    <input
                        type="date"
                        value={draftFilter.dateTo}
                        min={
                            draftFilter.dateFrom ||
                            undefined
                        }
                        onChange={(event) =>
                            onFilterChange((current) => ({
                                ...current,
                                dateTo:
                                event.target.value,
                            }))
                        }
                    />
                </label>

                {filtersDirty && (
                    <div className="audit-filter-notice">
                        Фильтры изменены. Нажмите
                        «Применить фильтры».
                    </div>
                )}

                {directoriesError && (
                    <div className="error">
                        {directoriesError}
                    </div>
                )}

                {filterError && (
                    <div className="error">
                        {filterError}
                    </div>
                )}

                <div className="filter-actions">
                    <button
                        type="button"
                        disabled={
                            loading || !filtersDirty
                        }
                        onClick={onApply}
                    >
                        Применить фильтры
                    </button>

                    <button
                        type="button"
                        className="secondary-button"
                        disabled={loading}
                        onClick={onReset}
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
    onClick: () => void
}

function PresetButton({
                          label,
                          onClick,
                      }: PresetButtonProps) {
    return (
        <button
            type="button"
            className="secondary-button"
            onClick={onClick}
        >
            {label}
        </button>
    )
}

function formatUserOption(
    user: User,
    superAdmin: boolean,
    organizationNameById: Map<string, string>,
): string {
    const fullName = user.fullName?.trim()

    const userLabel = fullName
        ? `${user.email} — ${fullName}`
        : user.email

    if (!superAdmin) {
        return userLabel
    }

    const organizationName =
        organizationNameById.get(
            user.organizationId,
        ) ?? user.organizationId

    return `${organizationName} • ${userLabel}`
}

export default AuditFilters

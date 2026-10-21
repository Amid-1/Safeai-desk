// frontend/src/features/audit/ui/auditFilters/AuditDateFields.tsx
// ============================================================
// frontend/src/features/audit/ui/auditFilters/AuditDateFields.tsx
// ============================================================
import type { AuditFiltersProps } from './auditFilterTypes'
import type { DatePreset } from '../types'
import { PresetButton } from './auditFilterControls'

type Props = Pick<AuditFiltersProps, 'draftFilter' | 'loading' | 'onFilterChange' | 'onDatePreset'> & {activePreset: DatePreset | null; setActivePreset: (value: DatePreset | null) => void}

export function AuditDateFields({
    draftFilter,
    loading,
    onFilterChange,
    onDatePreset,
    activePreset,
    setActivePreset,
}: Props) {
    return (
        <>
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
                            active={activePreset === 'today'}
                            onClick={() => {
                                setActivePreset('today')
                                onDatePreset('today')
                            }}
                        />

                        <PresetButton
                            label="Вчера"
                            disabled={loading}
                            active={activePreset === 'yesterday'}
                            onClick={() => {
                                setActivePreset('yesterday')
                                onDatePreset('yesterday')
                            }}
                        />

                        <PresetButton
                            label="7 дней"
                            disabled={loading}
                            active={activePreset === 'last7Days'}
                            onClick={() => {
                                setActivePreset('last7Days')
                                onDatePreset('last7Days')
                            }}
                        />

                        <PresetButton
                            label="30 дней"
                            disabled={loading}
                            active={activePreset === 'last30Days'}
                            onClick={() => {
                                setActivePreset('last30Days')
                                onDatePreset('last30Days')
                            }}
                        />

                        <PresetButton
                            label="365 дней"
                            disabled={loading}
                            active={activePreset === 'last365Days'}
                            onClick={() => {
                                setActivePreset('last365Days')
                                onDatePreset('last365Days')
                            }}
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
                            setActivePreset(null)

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
                            setActivePreset(null)

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
        </>
    )
}

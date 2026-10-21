// frontend/src/features/audit/ui/auditFilters/AuditSubmitActions.tsx
// ============================================================
// frontend/src/features/audit/ui/auditFilters/AuditSubmitActions.tsx
// ============================================================
import type { AuditFiltersProps } from './auditFilterTypes'
import type { DatePreset } from '../types'

type Props = Pick<AuditFiltersProps, 'directoriesLoading' | 'filterError' | 'filtersDirty' | 'loading' | 'onApply' | 'onReset'> & { setActivePreset: (value: DatePreset | null) => void; clearEventTypeSearch: () => void }

export function AuditSubmitActions({ directoriesLoading, filterError, filtersDirty, loading, onApply, onReset, setActivePreset, clearEventTypeSearch }: Props) {
    return (
        <>
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

                <div className="audit-filter-submit">
                    {filtersDirty && (
                        <div className="audit-filter-notice" role="status" aria-live="polite">
                            Фильтры изменены — результаты еще не обновлены.
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
                        onClick={() => {
                            setActivePreset('last30Days')
                            clearEventTypeSearch()
                            onReset()
                        }}
                    >
                        Последние 30 дней
                    </button>
                    </div>
                </div>
        </>
    )
}

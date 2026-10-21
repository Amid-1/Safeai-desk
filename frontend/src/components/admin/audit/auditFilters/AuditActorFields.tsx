// ============================================================
// frontend/src/components/admin/audit/auditFilters/AuditActorFields.tsx
// ============================================================
import type { AuditFiltersProps } from './auditFilterTypes'
import { buildVisibleActors, formatActorOption } from './auditDirectoryOptions'
import { DirectoryWarning } from './auditFilterControls'

type Props = Pick<
    AuditFiltersProps,
    'draftFilter' | 'actors' | 'loading' | 'directoriesLoading' | 'actorsError' | 'onFilterChange'
> & {
    actorQuery: string
    onActorQueryChange: (value: string) => void
}

export function AuditActorFields({
    draftFilter,
    actors,
    loading,
    directoriesLoading,
    actorsError,
    onFilterChange,
    actorQuery,
    onActorQueryChange,
}: Props) {
    const visibleActors = buildVisibleActors(actors, draftFilter.actorUserId)
    return (
        <>
                <div className="audit-actor-picker" role="group" aria-label="Инициатор события">
                    <p className="audit-actor-picker__title">Инициатор события</p>

                    <label className="audit-filter-field audit-filter-field--actor-search">
                        Найти пользователя
                        <input
                            type="search"
                            value={actorQuery}
                            maxLength={320}
                            disabled={loading}
                            autoComplete="off"
                            placeholder="Email, имя или UUID"
                            onChange={(event) => onActorQueryChange(event.target.value)}
                        />
                    </label>

                    <label className="audit-filter-field audit-filter-field--actor">
                        Инициатор
                        <select
                            value={draftFilter.actorUserId}
                            disabled={loading || directoriesLoading}
                            onChange={(event) => {
                                const actorUserId = event.target.value
                                onFilterChange((current) => ({ ...current, actorUserId }))
                            }}
                        >
                            <option value="">Все инициаторы</option>
                            {visibleActors.map((actor) => (
                                <option key={actor.actorUserId} value={actor.actorUserId}>
                                    {formatActorOption(actor)}
                                </option>
                            ))}
                        </select>
                    </label>

                    <small className="audit-actor-picker__help muted">
                        Поиск уточняет список; фильтр применится после выбора UUID.
                    </small>
                </div>

                <label className="audit-filter-field audit-filter-field--email">
                    Email инициатора или префикс

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
        </>
    )
}

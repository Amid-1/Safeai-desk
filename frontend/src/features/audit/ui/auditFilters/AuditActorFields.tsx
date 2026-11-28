// frontend/src/features/audit/ui/auditFilters/AuditActorFields.tsx
import type { AuditFiltersProps } from './auditFilterTypes'
import { buildVisibleActors } from './auditDirectoryOptions'
import { DirectoryWarning } from './auditFilterControls'
import { AuditSearchPicker } from './AuditSearchPicker'

type Props = Pick<AuditFiltersProps, 'draftFilter' | 'actors' | 'loading' | 'directoriesLoading'
    | 'actorsError' | 'onFilterChange'> & {
    actorQuery: string
    onActorQueryChange: (value: string) => void
}
export function AuditActorFields({ draftFilter, actors, loading, directoriesLoading,
    actorsError, onFilterChange, actorQuery, onActorQueryChange }: Props) {
    const needle = actorQuery.trim().toLocaleLowerCase('ru')
    const options = buildVisibleActors(actors, draftFilter.actorUserId).filter((actor) =>
        actor.actorUserId === draftFilter.actorUserId || !needle ||
        [actor.actorEmail, actor.actorDisplayName, actor.actorUserId].some((part) =>
            part?.toLocaleLowerCase('ru').includes(needle)))
        .map((actor) => ({
        value: actor.actorUserId,
        label: actor.actorDisplayName && actor.actorEmail
            ? actor.actorEmail + ' — ' + actor.actorDisplayName
            : actor.actorEmail || actor.actorDisplayName || 'Пользователь без имени (' + actor.actorUserId + ')',
    }))
    return <>
        <AuditSearchPicker label="Пользователь" placeholder="Имя или email пользователя"
            allLabel="Все пользователи и системные события" query={actorQuery}
            onQueryChange={onActorQueryChange} value={draftFilter.actorUserId}
            options={options} disabled={loading} pending={directoriesLoading}
            onSelect={(actorUserId) => onFilterChange((current) => ({ ...current, actorUserId }))} />
        <DirectoryWarning message={actorsError} />
    </>
}

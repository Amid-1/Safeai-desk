import type {
    ReactNode,
} from 'react'
import type {
    Organization,
} from '../../../api/organizationApi'

export function OrganizationStatusBadge({
    enabled,
}: {
    enabled: boolean
}) {
    return (
        <span
            className={
                enabled
                    ? (
                        'organization-status '
                        + 'organization-status--enabled'
                    )
                    : (
                        'organization-status '
                        + 'organization-status--disabled'
                    )
            }
        >
            <span
                className="organization-status__dot"
                aria-hidden="true"
            />
            {enabled
                ? 'Включена'
                : 'Отключена'}
        </span>
    )
}

export function OrganizationTypeBadge({
    type,
}: {
    type: Organization['type']
}) {
    return (
        <span
            className={
                type === 'PLATFORM'
                    ? (
                        'organization-type '
                        + 'organization-type--platform'
                    )
                    : (
                        'organization-type '
                        + 'organization-type--tenant'
                    )
            }
            title={type}
        >
            {type === 'PLATFORM'
                ? 'Платформенная'
                : 'Клиентская'}
        </span>
    )
}

export function OrganizationDetail({
    term,
    value,
}: {
    term: string
    value: ReactNode
}) {
    return (
        <div className="organization-details__row">
            <dt>{term}</dt>
            <dd>{value}</dd>
        </div>
    )
}

export function OrganizationImpact({
    term,
    value,
}: {
    term: string
    value: number
}) {
    return (
        <div className="organization-impact__row">
            <dt>{term}</dt>
            <dd>{value}</dd>
        </div>
    )
}

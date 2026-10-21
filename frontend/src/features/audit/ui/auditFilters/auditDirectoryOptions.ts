// frontend/src/features/audit/ui/auditFilters/auditDirectoryOptions.ts
// ============================================================
// frontend/src/features/audit/ui/auditFilters/auditDirectoryOptions.ts
// ============================================================
import type { AuditActorDirectoryItem, AuditTargetOrganizationDirectoryItem } from '../../../admin/api/adminApi'

export function buildVisibleActors(
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

export function buildVisibleOrganizations(
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

export function formatActorOption(
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

export function formatOrganizationOption(
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

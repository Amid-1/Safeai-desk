// ============================================================
// frontend/src/api/adminAudit/auditParsers.ts
// ============================================================

import { contractError, expectInstant, expectNullableString, expectNullableUuid, expectRecord, expectString, expectUuid } from '../runtime'
import type { AuditEvent, AuditActorDirectoryItem, AuditTargetOrganizationDirectoryItem } from './auditTypes'
import { MAX_AUDIT_EVENT_TYPE_LENGTH, MAX_AUDIT_EMAIL_LENGTH, MAX_AUDIT_DISPLAY_NAME_LENGTH } from './auditConstants'
import { parseAuditDetails } from './auditSanitizer'

export function parseAuditEvent(
    value: unknown,
    field = 'auditEvent',
): AuditEvent {
    const record = expectRecord(
        value,
        field,
    )

    const detailsResult =
        parseAuditDetails(
            record.details,
        )

    return {
        id: expectUuid(
            record.id,
            `${field}.id`,
        ),

        targetOrganizationId:
            expectUuid(
                record.targetOrganizationId
                    ?? record.organizationId,
                `${field}.targetOrganizationId`,
            ),

        targetOrganizationName:
            expectNullableString(
                record.targetOrganizationName
                    ?? record.organizationName
                    ?? null,
                `${field}.targetOrganizationName`,
                {
                    maxLength: 255,
                },
            ),

        actorUserId:
            expectNullableUuid(
                record.actorUserId
                    ?? record.userId
                    ?? null,
                `${field}.actorUserId`,
            ),

        actorOrganizationId:
            expectNullableUuid(
                record.actorOrganizationId
                    ?? null,
                `${field}.actorOrganizationId`,
            ),

        actorEmail:
            expectNullableString(
                record.actorEmail
                    ?? record.userEmail
                    ?? null,
                `${field}.actorEmail`,
                {
                    maxLength:
                        MAX_AUDIT_EMAIL_LENGTH,
                },
            ),

        actorDisplayName:
            expectNullableString(
                record.actorDisplayName
                    ?? record.userDisplayName
                    ?? null,
                `${field}.actorDisplayName`,
                {
                    maxLength:
                        MAX_AUDIT_DISPLAY_NAME_LENGTH,
                },
            ),

        eventType:
            parseAuditEventType(
                record.eventType,
                `${field}.eventType`,
            ),

        details:
            detailsResult.details,
        detailsTruncated:
            detailsResult.truncated,
        detailsInvalid:
            detailsResult.invalid,

        createdAt: expectInstant(
            record.createdAt,
            `${field}.createdAt`,
        ),
    }
}

export function parseAuditEventType(
    value: unknown,
    field: string,
): string {
    const eventType = expectString(
        value,
        field,
        {
            maxLength:
                MAX_AUDIT_EVENT_TYPE_LENGTH,
        },
    )

    if (
        !/^[A-Z][A-Z0-9_]*$/.test(
            eventType,
        )
    ) {
        throw contractError(
            `${field} имеет некорректный формат`,
        )
    }

    return eventType
}

export function parseAuditActorDirectoryItem(
    value: unknown,
    field: string,
): AuditActorDirectoryItem {
    const record = expectRecord(
        value,
        field,
    )

    return {
        actorUserId:
            expectNullableUuid(
                record.actorUserId
                    ?? record.userId
                    ?? null,
                `${field}.actorUserId`,
            ),
        actorOrganizationId:
            expectNullableUuid(
                record.actorOrganizationId
                    ?? null,
                `${field}.actorOrganizationId`,
            ),
        actorEmail:
            expectNullableString(
                record.actorEmail
                    ?? record.userEmail
                    ?? null,
                `${field}.actorEmail`,
                {
                    maxLength:
                        MAX_AUDIT_EMAIL_LENGTH,
                },
            ),
        actorDisplayName:
            expectNullableString(
                record.actorDisplayName
                    ?? record.userDisplayName
                    ?? null,
                `${field}.actorDisplayName`,
                {
                    maxLength:
                        MAX_AUDIT_DISPLAY_NAME_LENGTH,
                },
            ),
    }
}

export function parseAuditTargetOrganizationDirectoryItem(
    value: unknown,
    field: string,
): AuditTargetOrganizationDirectoryItem {
    const record = expectRecord(
        value,
        field,
    )

    return {
        targetOrganizationId:
            expectUuid(
                record.targetOrganizationId
                    ?? record.organizationId
                    ?? record.id,
                `${field}.targetOrganizationId`,
            ),
        targetOrganizationName:
            expectNullableString(
                record.targetOrganizationName
                    ?? record.organizationName
                    ?? record.name
                    ?? null,
                `${field}.targetOrganizationName`,
                {
                    maxLength: 255,
                },
            ),
    }
}

// frontend/src/entities/knowledge/api/knowledge/knowledgeParsers.ts
// ============================================================
// frontend/src/entities/knowledge/api/knowledge/knowledgeParsers.ts
// ============================================================

import { expectRecord, expectBoolean, expectEnum, expectInstant, expectNonNegativeInteger, expectNullableString, expectString, expectUuid } from '../../../../shared/api/runtime'
import { VISIBILITIES, ACCESS_LEVELS } from './knowledgeTypes'
import type { KnowledgeBase, KnowledgeBaseAccess, KnowledgeBaseMember, KnowledgeMemberCandidate } from './knowledgeTypes'

export function parseKnowledgeBase(
    value: unknown,
    field = 'knowledgeBase',
): KnowledgeBase {
    const record =
        expectRecord(
            value,
            field,
        )

    const description =
        record.description
            === undefined
            ? null
            : expectNullableString(
                record.description,
                `${field}.description`,
                {
                    maxLength:
                        2_000,
                },
            )

    return {
        id:
            expectUuid(
                record.id,
                `${field}.id`,
            ),

        organizationId:
            expectUuid(
                record.organizationId,
                `${field}.organizationId`,
            ),

        name:
            expectString(
                record.name,
                `${field}.name`,
                {
                    maxLength:
                        255,
                },
            ),

        description,

        visibility:
            expectEnum(
                record.visibility,
                `${field}.visibility`,
                VISIBILITIES,
            ),

        enabled:
            expectBoolean(
                record.enabled,
                `${field}.enabled`,
            ),

        createdByUserId:
            expectUuid(
                record.createdByUserId,
                `${field}.createdByUserId`,
            ),

        version:
            expectNonNegativeInteger(
                record.version,
                `${field}.version`,
            ),

        createdAt:
            expectInstant(
                record.createdAt,
                `${field}.createdAt`,
            ),

        updatedAt:
            expectInstant(
                record.updatedAt,
                `${field}.updatedAt`,
            ),
    }
}

export function parseKnowledgeBaseAccess(
    value: unknown,
    field = 'knowledgeBaseAccess',
): KnowledgeBaseAccess {
    const record = expectRecord(
        value,
        field,
    )

    return {
        knowledgeBaseId: expectUuid(
            record.knowledgeBaseId,
            `${field}.knowledgeBaseId`,
        ),
        accessLevel: expectEnum(
            record.accessLevel,
            `${field}.accessLevel`,
            ACCESS_LEVELS,
        ),
        administrator: expectBoolean(
            record.administrator,
            `${field}.administrator`,
        ),
        canEditDocuments: expectBoolean(
            record.canEditDocuments,
            `${field}.canEditDocuments`,
        ),
        canManageBase: expectBoolean(
            record.canManageBase,
            `${field}.canManageBase`,
        ),
        canManageMembers: expectBoolean(
            record.canManageMembers,
            `${field}.canManageMembers`,
        ),
    }
}

export function parseKnowledgeBaseMember(
    value: unknown,
    field = 'member',
): KnowledgeBaseMember {
    const record =
        expectRecord(
            value,
            field,
        )

    const fullName =
        record.fullName
            === undefined
            ? null
            : expectNullableString(
                record.fullName,
                `${field}.fullName`,
                {
                    maxLength:
                        255,
                },
            )

    return {
        knowledgeBaseId:
            expectUuid(
                record.knowledgeBaseId,
                `${field}.knowledgeBaseId`,
            ),

        userId:
            expectUuid(
                record.userId,
                `${field}.userId`,
            ),

        email:
            expectString(
                record.email,
                `${field}.email`,
                {
                    maxLength:
                        255,
                },
            ),

        fullName,

        accessLevel:
            expectEnum(
                record.accessLevel,
                `${field}.accessLevel`,
                ACCESS_LEVELS,
            ),

        version:
            expectNonNegativeInteger(
                record.version,
                `${field}.version`,
            ),

        createdAt:
            expectInstant(
                record.createdAt,
                `${field}.createdAt`,
            ),

        updatedAt:
            expectInstant(
                record.updatedAt,
                `${field}.updatedAt`,
            ),
    }
}

export function parseKnowledgeMemberCandidate(
    value: unknown,
    field: string,
): KnowledgeMemberCandidate {
    const record =
        expectRecord(
            value,
            field,
        )

    return {
        userId:
            expectUuid(
                record.userId,
                `${field}.userId`,
            ),

        email:
            expectString(
                record.email,
                `${field}.email`,
                {
                    maxLength:
                        255,
                },
            ),

        fullName:
            record.fullName
                === undefined
                ? null
                : expectNullableString(
                    record.fullName,
                    `${field}.fullName`,
                    {
                        maxLength:
                            255,
                    },
                ),
    }
}

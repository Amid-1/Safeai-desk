// ============================================================
// frontend/src/features/audit/api/adminAudit/auditSanitizer.ts
// ============================================================

import { isRecord } from '../../../../shared/api/runtime'
import type { JsonValue } from './auditTypes'
import { MAX_AUDIT_DETAILS_DEPTH, MAX_AUDIT_DETAILS_NODES, MAX_AUDIT_DETAILS_KEYS, MAX_AUDIT_DETAILS_KEY_LENGTH, MAX_AUDIT_DETAILS_ARRAY_ITEMS, MAX_AUDIT_DETAILS_STRING_LENGTH, MAX_AUDIT_DETAILS_TOTAL_CHARACTERS, SENSITIVE_AUDIT_KEYS } from './auditConstants'

export function parseAuditDetails(
    value: unknown,
): {
    details: Record<string, JsonValue>
    truncated: boolean
    invalid: boolean
} {
    if (!isRecord(value)) {
        return {
            details: {},
            truncated: false,
            invalid:
                value !== undefined
                && value !== null,
        }
    }

    const state = {
        nodes: 0,
        characters: 0,
        truncated: false,
    }

    const parsed = sanitizeJsonValue(
        value,
        0,
        state,
    )

    if (!isRecord(parsed)) {
        return {
            details: {},
            truncated:
                state.truncated,
            invalid: true,
        }
    }

    return {
        details:
            parsed as Record<
                string,
                JsonValue
            >,
        truncated:
            state.truncated,
        invalid: false,
    }
}

function sanitizeJsonValue(
    value: unknown,
    depth: number,
    state: {
        nodes: number
        characters: number
        truncated: boolean
    },
): JsonValue {
    state.nodes += 1

    if (
        state.nodes
            > MAX_AUDIT_DETAILS_NODES
        || depth
            > MAX_AUDIT_DETAILS_DEPTH
    ) {
        state.truncated = true
        return '[TRUNCATED]'
    }

    if (
        value === null
        || typeof value === 'boolean'
    ) {
        return value
    }

    if (typeof value === 'string') {
        const remainingCharacters =
            Math.max(
                0,
                MAX_AUDIT_DETAILS_TOTAL_CHARACTERS
                - state.characters,
            )

        const allowedLength =
            Math.min(
                MAX_AUDIT_DETAILS_STRING_LENGTH,
                remainingCharacters,
            )

        if (
            value.length > allowedLength
        ) {
            state.truncated = true
        }

        const result =
            value.slice(
                0,
                allowedLength,
            )

        state.characters +=
            result.length

        return value.length > allowedLength
            ? `${result}…[TRUNCATED]`
            : result
    }

    if (typeof value === 'number') {
        if (!Number.isFinite(value)) {
            state.truncated = true
            return '[INVALID_NUMBER]'
        }

        if (
            Number.isInteger(value)
            && !Number.isSafeInteger(value)
        ) {
            state.truncated = true
            return '[UNSAFE_INTEGER]'
        }

        return value
    }

    if (Array.isArray(value)) {
        if (
            value.length
                > MAX_AUDIT_DETAILS_ARRAY_ITEMS
        ) {
            state.truncated = true
        }

        return value
            .slice(
                0,
                MAX_AUDIT_DETAILS_ARRAY_ITEMS,
            )
            .map(
                (item) =>
                    sanitizeJsonValue(
                        item,
                        depth + 1,
                        state,
                    ),
            )
    }

    if (!isRecord(value)) {
        state.truncated = true
        return '[UNSUPPORTED_VALUE]'
    }

    const result =
        Object.create(
            null,
        ) as Record<string, JsonValue>

    const entries =
        Object.entries(value)

    if (
        entries.length
            > MAX_AUDIT_DETAILS_KEYS
    ) {
        state.truncated = true
    }

    entries
        .slice(
            0,
            MAX_AUDIT_DETAILS_KEYS,
        )
        .forEach(
            (
                [originalKey, nested],
                index,
            ) => {
                const key =
                    sanitizeAuditDetailKey(
                        originalKey,
                        index,
                        state,
                        result,
                    )

                if (
                    isSensitiveAuditKey(
                        originalKey,
                    )
                ) {
                    result[key] =
                        '[REDACTED]'
                    return
                }

                result[key] =
                    sanitizeJsonValue(
                        nested,
                        depth + 1,
                        state,
                    )
            },
        )

    return result
}

function sanitizeAuditDetailKey(
    originalKey: string,
    index: number,
    state: {
        nodes: number
        characters: number
        truncated: boolean
    },
    current:
        Record<string, JsonValue>,
): string {
    const dangerousKey =
        originalKey === '__proto__'
        || originalKey === 'prototype'
        || originalKey === 'constructor'

    const remainingCharacters =
        Math.max(
            0,
            MAX_AUDIT_DETAILS_TOTAL_CHARACTERS
            - state.characters,
        )

    const allowedLength =
        Math.min(
            MAX_AUDIT_DETAILS_KEY_LENGTH,
            remainingCharacters,
        )

    let candidate =
        dangerousKey
            ? `[BLOCKED_KEY_${index}]`
            : originalKey.slice(
                0,
                allowedLength,
            )

    if (
        dangerousKey
        || originalKey.length
            > allowedLength
    ) {
        state.truncated = true
    }

    if (!candidate) {
        candidate =
            `[TRUNCATED_KEY_${index}]`
    }

    if (
        Object.prototype.hasOwnProperty.call(
            current,
            candidate,
        )
    ) {
        candidate =
            `${candidate}#${index}`
    }

    state.characters +=
        candidate.length

    return candidate
}

function isSensitiveAuditKey(
    key: string,
): boolean {
    const normalized = key
        .toLowerCase()
        .replace(/[^a-z0-9]/g, '')

    return SENSITIVE_AUDIT_KEYS.has(
        normalized,
    )
}

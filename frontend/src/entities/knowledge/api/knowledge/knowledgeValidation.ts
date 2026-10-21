// frontend/src/entities/knowledge/api/knowledge/knowledgeValidation.ts
// ============================================================
// frontend/src/entities/knowledge/api/knowledge/knowledgeValidation.ts
// ============================================================



export function normalizeCandidateLimit(
    value: number,
): number {
    if (
        !Number.isFinite(
            value,
        )
    ) {
        return 20
    }

    return Math.min(
        Math.max(
            1,
            Math.trunc(
                value,
            ),
        ),
        50,
    )
}

export function assertExpectedVersion(
    expectedVersion: number,
): void {
    if (
        !Number.isSafeInteger(
            expectedVersion,
        )
        || expectedVersion < 0
    ) {
        throw new Error(
            'expectedVersion должен быть неотрицательным целым числом.',
        )
    }
}

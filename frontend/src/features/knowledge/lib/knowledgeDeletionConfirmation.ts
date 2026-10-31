// frontend/src/features/knowledge/lib/knowledgeDeletionConfirmation.ts
/**
 * Confirmation UX only; never an authorization boundary. The API uses entity UUID,
 * If-Match, Idempotency-Key and server-side ACL/feature/retention checks.
 *
 * Unicode NFC and letter case are normalized for typing convenience. Different
 * scripts and lookalike characters (Latin X / Cyrillic Х; digit 3 / Cyrillic З)
 * MUST remain distinct. Do not use NFKC or confusable transliteration here.
 */
export function matchesKnowledgeDeletionName(typed: string, expected: string): boolean {
    const normalized = (value: string) => value.normalize('NFC').trim().toLocaleLowerCase('ru-RU')
    return expected.trim().length > 0 && normalized(typed) === normalized(expected)
}

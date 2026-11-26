// frontend/src/shared/ui/erasureContextTarget.ts
export type ContextTarget = { type: 'CHAT' | 'KNOWLEDGE_BASE' | 'KNOWLEDGE_DOCUMENT'; id: string; baseId?: string }
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
export function parseContextTarget(value: unknown): ContextTarget | null {
    if (!value || typeof value !== 'object') return null
    const v = value as Record<string, unknown>
    if (typeof v.id !== 'string' || !UUID.test(v.id)) return null
    if (v.type === 'CHAT' || v.type === 'KNOWLEDGE_BASE') return { type: v.type, id: v.id }
    if (v.type === 'KNOWLEDGE_DOCUMENT' && typeof v.baseId === 'string' && UUID.test(v.baseId)) {
        return { type: v.type, id: v.id, baseId: v.baseId }
    }
    return null
}

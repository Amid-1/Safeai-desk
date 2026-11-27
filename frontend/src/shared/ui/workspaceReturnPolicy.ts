// frontend/src/shared/ui/workspaceReturnPolicy.ts
const sources: Record<string, string> = {
    '/chat': 'К чатам', '/knowledge': 'К базам знаний',
    '/admin/retention': 'К политике хранения', '/admin/erasure': 'К управлению данными',
    '/admin/erasure/registry': 'К списку заявок', '/admin/erasure/intake': 'К заявкам организации',
    '/admin/erasure/requests': 'К списку согласований', '/admin/erasure/history': 'К истории изменений',
    '/my/erasure-requests': 'К моим обращениям', '/settings/privacy': 'К моим данным',
}
const registryCard = /^\/admin\/erasure\/registry\/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/
export function safeReturn(value: unknown): { to: string; label: string; scroll: number } | null {
    if (!value || typeof value !== 'object') return null
    const v = value as Record<string, unknown>
    if (typeof v.from !== 'string' || v.from.length > 2048 || /[\\#\r\n]/.test(v.from)) return null
    const path = v.from.split('?')[0]
    if (!isWorkspaceReturnPath(path)) return null
    return { to: v.from, label: sources[path] ?? (registryCard.test(path) ? 'К карточке заявки' : 'К базе знаний'), scroll: typeof v.scroll === 'number' && Number.isFinite(v.scroll)
        ? Math.max(0, Math.min(v.scroll, 1000000)) : 0 }
}
export function isWorkspaceReturnPath(path: string): boolean {
    return Object.prototype.hasOwnProperty.call(sources, path)
        || registryCard.test(path)
        || /^\/knowledge\/[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(path)
}

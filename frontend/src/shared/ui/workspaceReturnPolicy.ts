// frontend/src/shared/ui/workspaceReturnPolicy.ts
const sources: Record<string, string> = {
    '/admin/retention': 'К политике хранения', '/admin/erasure': 'К управлению данными',
    '/admin/erasure/registry': 'К списку заявок', '/admin/erasure/intake': 'К заявкам организации',
    '/admin/erasure/requests': 'К списку согласований', '/admin/erasure/history': 'К истории изменений',
    '/my/erasure-requests': 'К моим обращениям', '/settings/privacy': 'К моим данным',
}
export function safeReturn(value: unknown): { to: string; label: string; scroll: number } | null {
    if (!value || typeof value !== 'object') return null
    const v = value as Record<string, unknown>
    if (typeof v.from !== 'string' || v.from.length > 2048 || /[\\#\r\n]/.test(v.from)) return null
    const path = v.from.split('?')[0]
    if (!isWorkspaceReturnPath(path)) return null
    return { to: v.from, label: sources[path], scroll: typeof v.scroll === 'number' && Number.isFinite(v.scroll)
        ? Math.max(0, Math.min(v.scroll, 1000000)) : 0 }
}
export function isWorkspaceReturnPath(path: string): boolean {
    return Object.prototype.hasOwnProperty.call(sources, path)
}

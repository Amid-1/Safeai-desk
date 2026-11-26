// frontend/src/features/erasure/api/notificationsApi.ts
import { apiRequest } from '../../../shared/api/http'
import { expectRecord } from '../../../shared/api/runtime'

export type NotificationEvent = { eventId: string; requestId: string; eventCode: string; createdAt: string; audience: 'PERSONAL' | 'REVIEW' }
export type NotificationFeed = { canRequest: boolean; registryCount: number | null; reviewCount: number | null; personal: NotificationEvent[]; review: NotificationEvent[] }
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const codes = { PERSONAL: ['APPROVED_REVIEW_ONLY', 'REJECTED', 'NEEDS_INFORMATION'], REVIEW: ['SUBMITTED', 'INFORMATION_PROVIDED'] }
function count(value: unknown): number | null {
    if (value === null) return null
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('Некорректный счётчик.')
    return value
}
function events(value: unknown, audience: NotificationEvent['audience']): NotificationEvent[] {
    if (!Array.isArray(value) || value.length > 20) throw new Error('Некорректные уведомления.')
    return value.map((item: unknown) => {
        const d = expectRecord(item, 'erasure.notification')
        if (typeof d.eventId !== 'string' || !UUID.test(d.eventId) || typeof d.requestId !== 'string' || !UUID.test(d.requestId)
            || typeof d.eventCode !== 'string' || !codes[audience].includes(d.eventCode) || d.audience !== audience
            || typeof d.createdAt !== 'string' || !Number.isFinite(Date.parse(d.createdAt))) throw new Error('Некорректное уведомление.')
        return { eventId: d.eventId, requestId: d.requestId, eventCode: d.eventCode, createdAt: d.createdAt, audience }
    })
}
export async function loadNotifications(signal?: AbortSignal): Promise<NotificationFeed> {
    const d = expectRecord(await apiRequest('/api/erasure/requests/notifications', { method: 'GET', signal }), 'erasure.notifications')
    if (typeof d.canRequest !== 'boolean') throw new Error('Некорректный доступ.')
    const feed = { canRequest: d.canRequest, registryCount: count(d.registryCount), reviewCount: count(d.reviewCount),
        personal: events(d.personal, 'PERSONAL'), review: events(d.review, 'REVIEW') }
    if ((!feed.canRequest && feed.personal.length) || (feed.reviewCount === null && feed.review.length)) throw new Error('Некорректная область уведомлений.')
    return feed
}

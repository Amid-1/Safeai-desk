// frontend/src/features/erasure/review/reviewApi.ts
import { apiRequest } from '../../../shared/api/http'
import { expectRecord } from '../../../shared/api/runtime'

export type ReviewState = 'PENDING' | 'NEEDS_INFORMATION' | 'APPROVED_REVIEW_ONLY'
    | 'REJECTED' | 'WITHDRAWN'
export type Decision = 'APPROVED_REVIEW_ONLY' | 'REJECTED' | 'NEEDS_INFORMATION'

export type ReviewView = {
    requestId: string
    requesterUserId: string
    resourceType: string
    resourceId: string
    operation: string
    scope: string
    baseState: string
    reviewState: ReviewState
    version: number
    holdActive: boolean
    reasonCode: string | null
    createdAt: string
    updatedAt: string
    executionAvailable: false
    completionProven: false
}
export type ReviewEvent = {
    version: number
    eventCode: string
    reasonCode: string | null
    createdAt: string
}

const STATES = new Set<ReviewState>([
    'PENDING', 'NEEDS_INFORMATION', 'APPROVED_REVIEW_ONLY', 'REJECTED', 'WITHDRAWN',
])
const DECISIONS = new Set<Decision>(['APPROVED_REVIEW_ONLY', 'REJECTED', 'NEEDS_INFORMATION'])

function str(value: unknown): string {
    if (typeof value !== 'string' || value.length === 0) throw new Error('Некорректный ответ сервера.')
    return value
}
function nonNegativeInt(value: unknown): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
        throw new Error('Некорректная версия заявки.')
    }
    return value
}
function isFalse(value: unknown): false {
    if (value !== false) throw new Error('Сервер не подтвердил, что исполнение выключено.')
    return false
}
function parseReview(value: unknown): ReviewView {
    const d = expectRecord(value, 'erasure.review')
    const state = str(d.reviewState)
    if (!STATES.has(state as ReviewState) || typeof d.holdActive !== 'boolean') {
        throw new Error('Неизвестное состояние рассмотрения.')
    }
    return {
        requestId: str(d.requestId), requesterUserId: str(d.requesterUserId),
        resourceType: str(d.resourceType), resourceId: str(d.resourceId),
        operation: str(d.operation), scope: str(d.scope), baseState: str(d.baseState),
        reviewState: state as ReviewState, version: nonNegativeInt(d.version),
        holdActive: d.holdActive, reasonCode: d.reasonCode == null ? null : str(d.reasonCode),
        createdAt: str(d.createdAt), updatedAt: str(d.updatedAt),
        executionAvailable: isFalse(d.executionAvailable), completionProven: isFalse(d.completionProven),
    }
}
function parsePage(value: unknown): ReviewView[] {
    const d = expectRecord(value, 'erasure.review.page')
    if (!Array.isArray(d.items) || d.items.length > 100) throw new Error('Некорректная очередь.')
    return d.items.map(parseReview)
}
function parseHistory(value: unknown): ReviewEvent[] {
    const d = expectRecord(value, 'erasure.review.history')
    if (!Array.isArray(d.items) || d.items.length > 100) throw new Error('Некорректная история.')
    return d.items.map((item: unknown) => {
        const event = expectRecord(item, 'erasure.review.event')
        return { version: nonNegativeInt(event.version), eventCode: str(event.eventCode),
            reasonCode: event.reasonCode == null ? null : str(event.reasonCode),
            createdAt: str(event.createdAt) }
    })
}
function key(): string { return crypto.randomUUID() }
function tag(version: number): string { return `"${version}"` }
export async function loadReviewQueue(signal?: AbortSignal): Promise<ReviewView[]> {
    return parsePage(await apiRequest('/api/admin/erasure/reviews', { method: 'GET', signal }))
}
export async function loadReview(id: string, signal?: AbortSignal): Promise<ReviewView> {
    return parseReview(await apiRequest(`/api/admin/erasure/reviews/${encodeURIComponent(id)}`, {
        method: 'GET', signal,
    }))
}
export async function loadMyReview(id: string, signal?: AbortSignal): Promise<ReviewView> {
    return parseReview(await apiRequest(`/api/erasure/requests/${encodeURIComponent(id)}/review`, {
        method: 'GET', signal,
    }))
}
export async function loadReviewHistory(id: string, forReviewer: boolean,
    signal?: AbortSignal): Promise<ReviewEvent[]> {
    const prefix = forReviewer ? '/api/admin/erasure/reviews'
        : '/api/erasure/requests'
    const suffix = forReviewer ? '/events' : '/review-events'
    return parseHistory(await apiRequest(`${prefix}/${encodeURIComponent(id)}${suffix}`, {
        method: 'GET', signal,
    }))
}
export async function reviewDecision(current: ReviewView, decision: Decision,
    reasonCode: string): Promise<ReviewView> {
    if (!DECISIONS.has(decision)) throw new Error('Недопустимое решение.')
    return parseReview(await apiRequest(`/api/admin/erasure/reviews/${current.requestId}/decision`, {
        method: 'POST', headers: { 'If-Match': tag(current.version), 'Idempotency-Key': key() },
        json: { decision, reasonCode },
    }))
}
export async function applyLegalHold(current: ReviewView, reasonCode: string): Promise<ReviewView> {
    return parseReview(await apiRequest(`/api/admin/erasure/reviews/${current.requestId}/holds`, {
        method: 'POST', headers: { 'If-Match': tag(current.version), 'Idempotency-Key': key() },
        json: { reasonCode },
    }))
}
export async function releaseLegalHold(current: ReviewView): Promise<ReviewView> {
    return parseReview(await apiRequest(`/api/admin/erasure/reviews/${current.requestId}/holds/release`, {
        method: 'POST', headers: { 'If-Match': tag(current.version), 'Idempotency-Key': key() },
    }))
}
export async function provideReviewInformation(current: ReviewView): Promise<ReviewView> {
    return parseReview(await apiRequest(`/api/erasure/requests/${current.requestId}/provide-information`, {
        method: 'POST', headers: { 'If-Match': tag(current.version), 'Idempotency-Key': key() },
    }))
}

export type ReviewAccess = { reviewer: boolean; legal: boolean }
export async function loadReviewAccess(signal?: AbortSignal): Promise<ReviewAccess> {
    const data = expectRecord(await apiRequest('/api/admin/erasure/reviews/access', {
        method: 'GET', signal,
    }), 'erasure.review.access')
    if (typeof data.reviewer !== 'boolean' || typeof data.legal !== 'boolean') {
        throw new Error('Сервер вернул некорректные полномочия.')
    }
    return { reviewer: data.reviewer, legal: data.legal }
}

export type ReviewSummary = {
    total: number
    pending: number
    held: number
    needsInformation: number
}
export async function loadReviewSummary(signal?: AbortSignal): Promise<ReviewSummary> {
    const d = expectRecord(await apiRequest('/api/admin/erasure/overview', {
        method: 'GET', signal,
    }), 'erasure.review.summary')
    return {
        total: nonNegativeInt(d.total), pending: nonNegativeInt(d.pending),
        held: nonNegativeInt(d.held), needsInformation: nonNegativeInt(d.needsInformation),
    }
}

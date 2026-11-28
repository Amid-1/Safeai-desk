// frontend/src/features/erasure/ui/ErasureNotifications.test.tsx
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { ErasureNotifications } from './ErasureNotifications'
import type { NotificationFeed } from '../api/notificationsApi'
const requestId = '83155da1-4fae-4629-abde-9e09aa500b13'
const feed: NotificationFeed = { canRequest: true, registryCount: null, reviewCount: null, review: [],
    personal: [{ eventId: requestId, requestId, audience: 'PERSONAL', eventCode: 'APPROVED_REVIEW_ONLY', createdAt: '2026-11-26T00:00:00Z' }] }
describe('Notification presentation', () => {
    it('links only to the requester view and never claims deletion', () => {
        render(<MemoryRouter><ErasureNotifications feed={feed} /></MemoryRouter>)
        expect(screen.getByText('Заявка согласована (удаление не начато)').closest('a')).toHaveAttribute('href', `/my/erasure-requests/${requestId}`)
    })
    it('does not expose notifications when the feed is absent', () => {
        render(<ErasureNotifications feed={null} />)
        expect(screen.queryByText('Последние события по заявкам')).not.toBeInTheDocument()
    })
})

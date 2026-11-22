// frontend/src/features/chat/ui/ChatMessageViews.pending.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { createPendingTurn } from '../lib/chatPage.helpers'
import type { PendingTurnStatus } from '../lib/chatPage.helpers'
import { PendingTurnState } from './ChatMessageViews'

function renderPending(status: PendingTurnStatus, retryAfterSeconds = 0) {
    const onCheck = vi.fn()
    render(<PendingTurnState
        pending={{ ...createPendingTurn('chat', 'text', 'request'), status }}
        now={1_000}
        retryAfterSeconds={retryAfterSeconds}
        onRetry={vi.fn()}
        onCheck={onCheck}
        onPrepareNew={vi.fn()}
        onCopy={vi.fn()}
        onDismissUnsafe={vi.fn()}
    />)
    return onCheck
}

describe('Допустимые действия при отправке сообщения', () => {
    it('обязательная сверка: только проверка и копирование', () => {
        const onCheck = renderPending('RECONCILIATION_REQUIRED')
        fireEvent.click(screen.getByRole('button', { name: 'Проверить статус' }))
        expect(onCheck).toHaveBeenCalledTimes(1)
        expect(screen.getByRole('button', { name: 'Скопировать текст' })).toBeEnabled()
        expect(screen.getAllByRole('button')).toHaveLength(2)
    })

    it('до завершения паузы 429 повтор заблокирован', () => {
        renderPending('RATE_LIMITED', 10)
        expect(screen.getByRole('button', { name: 'Повторить отправку' })).toBeDisabled()
        expect(screen.queryByRole('button', { name: 'Вернуть текст как новый запрос' })).toBeNull()
    })

    it('не предлагает повторить запрос с неоднозначным результатом', () => {
        renderPending('AMBIGUOUS')
        expect(screen.queryByRole('button', { name: 'Повторить отправку' })).toBeNull()
        expect(screen.getByRole('button', { name: 'Закрыть без повтора' })).toBeEnabled()
    })
})

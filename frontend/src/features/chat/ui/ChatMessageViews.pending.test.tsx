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
        expect(screen.queryByRole('button', { name: 'Редактировать сообщение' })).toBeNull()
    })

    it('не предлагает повторить запрос с неоднозначным результатом', () => {
        renderPending('AMBIGUOUS')
        expect(screen.queryByRole('button', { name: 'Повторить отправку' })).toBeNull()
        expect(screen.getByRole('button', { name: 'Закрыть без повтора' })).toBeEnabled()
    })

    it('скрывает номер обращения при неопределённой отправке и сохраняет проверку статуса', () => {
        const id = 'dbffa081-d3bc-4f1b-b8b8-b7520bba9000'
        render(<PendingTurnState pending={{...createPendingTurn('chat', 'text', 'request'),
            status: 'SEND_UNKNOWN', error: 'Статус отправки неизвестен. Номер обращения: ' + id}}
            now={1000} retryAfterSeconds={0} onRetry={vi.fn()} onCheck={vi.fn()}
            onPrepareNew={vi.fn()} onCopy={vi.fn()} onDismissUnsafe={vi.fn()}/>)
        expect(screen.getByText(id).closest('details')).not.toHaveAttribute('open')
        expect(screen.getByRole('button', {name: 'Проверить статус'})).toBeEnabled()
        expect(screen.queryByRole('button', {name: 'Редактировать сообщение'})).toBeNull()
    })

    it('показывает понятный отказ входного лимита и скрывает номер в подробностях', () => {
        const decision = '4fc8c165-c287-4f60-8a7f-9e12b3cf1f5d'
        const onPrepareNew = vi.fn()
        const onNewChat = vi.fn()
        render(<PendingTurnState
            pending={{ ...createPendingTurn('chat', 'text', 'request'), status: 'FAILED',
                error: 'Превышен лимит объёма входных данных. Обратитесь к администратору. '
                    + 'Номер решения: ' + decision }}
            now={1000} retryAfterSeconds={0} onRetry={vi.fn()} onCheck={vi.fn()}
            onPrepareNew={onPrepareNew} onCopy={vi.fn()} onDismissUnsafe={vi.fn()} onNewChat={onNewChat}
        />)
        expect(screen.getByRole('alert')).toHaveTextContent('Общий объём запроса')
        expect(screen.getByText(decision).closest('details')).not.toHaveAttribute('open')
        fireEvent.click(screen.getByRole('button', { name: 'Редактировать сообщение' }))
        fireEvent.click(screen.getByRole('button', { name: 'Создать новый чат' }))
        expect(onPrepareNew).toHaveBeenCalledOnce()
        expect(onNewChat).toHaveBeenCalledOnce()
    })
})

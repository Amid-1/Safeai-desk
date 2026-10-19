/* frontend/src/components/chat/ChatHistoryRow.test.tsx */
import {
    fireEvent,
    render,
    screen,
} from '@testing-library/react'
import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import type {
    Chat,
} from '../../api/chatApi'
import ChatHistoryRow from './ChatHistoryRow'

const chat: Chat = {
    id: 'cb145d98-55be-4ee1-a38a-1bded69520c1',
    title: 'Подробный вопрос о настройке корпоративной модели с длинным названием',
    createdAt: '2026-10-17T12:00:00.000Z',
    updatedAt: '2026-10-17T12:01:00.000Z',
}

function renderRow() {
    const onRename = vi.fn()
    const onArchive = vi.fn()
    const onOpen = vi.fn()
    render(
        <ChatHistoryRow
            chat={chat}
            active
            now={Date.now()}
            opening={false}
            archiving={false}
            onOpen={onOpen}
            onRename={onRename}
            onArchive={onArchive}
        />,
    )
    return { onRename, onArchive, onOpen }
}

describe('ChatHistoryRow', () => {
    it('shows the full title on hover and does not rename on open', () => {
        const { onOpen, onRename } = renderRow()
        const title = screen.getByText(chat.title)
        expect(title).toHaveAttribute('title', chat.title)
        fireEvent.click(screen.getByRole('button', {
            name: `Открыть чат «${chat.title}»`,
        }))
        expect(onOpen).toHaveBeenCalledWith(chat.id)
        expect(onRename).not.toHaveBeenCalled()
    })

    it('opens keyboard-accessible actions and requests explicit rename', () => {
        const { onRename, onArchive } = renderRow()
        const menu = screen.getByRole('button', {
            name: `Действия с чатом «${chat.title}»`,
        })
        expect(menu).toHaveAttribute('aria-expanded', 'false')
        fireEvent.click(menu)
        expect(menu).toHaveAttribute('aria-expanded', 'true')
        fireEvent.click(screen.getByRole('button', { name: 'Переименовать' }))
        expect(onRename).toHaveBeenCalledWith(chat)
        expect(onArchive).not.toHaveBeenCalled()
        expect(menu).toHaveAttribute('aria-expanded', 'false')
    })

    it('does not archive until the user chooses the archive action', () => {
        const { onArchive } = renderRow()
        fireEvent.click(screen.getByRole('button', {
            name: `Действия с чатом «${chat.title}»`,
        }))
        expect(onArchive).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('button', { name: 'Убрать из списка' }))
        expect(onArchive).toHaveBeenCalledWith(chat)
    })

    it('closes the actions with Escape without triggering either mutation', () => {
        const { onRename, onArchive } = renderRow()
        const toggle = screen.getByRole('button', {
            name: `Действия с чатом «${chat.title}»`,
        })
        fireEvent.click(toggle)
        fireEvent.keyDown(toggle, { key: 'Escape' })
        expect(toggle).toHaveAttribute('aria-expanded', 'false')
        expect(onRename).not.toHaveBeenCalled()
        expect(onArchive).not.toHaveBeenCalled()
    })
})

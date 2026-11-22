// frontend/src/features/chat/controller/ChatSidebar.test.tsx
import {fireEvent, render, screen} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {chat} from '../../../test/fixtures/chatApiFixtures'
import ChatSidebar from './ChatSidebar'
import type {ComponentProps} from 'react'

const auth = vi.hoisted(() => ({roles: ['ADMIN']}))
vi.mock('../../auth/state/useAuth', () => ({useAuth: () => ({currentUser: auth})}))
vi.mock('./ChatArchiveModal', () => ({
    default: ({initialChatId}: {initialChatId?: string}) => <p>Архив: {initialChatId}</p>,
}))

const item = chat({title: 'Рабочий чат'})

function mount(overrides: Partial<ComponentProps<typeof ChatSidebar>['controller']> = {}) {
    const handleArchiveChat = vi.fn(async () => true)
    render(<ChatSidebar controller={{
        chats: [item], chatCreating: false, chatsLoading: false, pendingTurns: {},
        activeChat: {...item, messages: []}, openingChatId: null, archivingChatId: null,
        chatHasNext: false, moreChatsLoading: false, clock: Date.now(),
        handleCreateChat: vi.fn(async () => null), handleArchiveChat,
        setChatToRename: vi.fn(), setChatToArchive: vi.fn(), openChat: vi.fn(),
        loadMoreChats: vi.fn(), setReloadToken: vi.fn(), ...overrides,
    }}/>)
    fireEvent.click(screen.getByRole('button', {name: 'Действия с чатом «Рабочий чат»'}))
    return handleArchiveChat
}

beforeEach(() => { auth.roles = ['ADMIN'] })

describe('Удаление из рабочего списка', () => {
    it('архивирует только после подтверждения и открывает выбранный чат', async () => {
        const archive = mount()
        fireEvent.click(screen.getByRole('button', {name: 'Удалить содержимое…'}))
        expect(archive).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('button', {name: 'Архивировать и продолжить'}))
        await screen.findByText('Архив: ' + item.id)
        expect(archive).toHaveBeenCalledExactlyOnceWith(item)
    })

    it('не показывает действие пользователю без ADMIN', () => {
        auth.roles = ['USER']
        const archive = mount()
        expect(screen.queryByRole('button', {name: 'Удалить содержимое…'})).toBeNull()
        expect(screen.getByRole('button', {name: 'Архивировать'})).toBeEnabled()
        expect(archive).not.toHaveBeenCalled()
    })

    it('при отказе архивации не открывает окно удаления', async () => {
        const archive = vi.fn(async () => false)
        mount({handleArchiveChat: archive})
        fireEvent.click(screen.getByRole('button', {name: 'Удалить содержимое…'}))
        fireEvent.click(screen.getByRole('button', {name: 'Архивировать и продолжить'}))
        await screen.findByText(/Операция не выполнена/)
        expect(screen.queryByText('Архив: ' + item.id)).toBeNull()
    })
})

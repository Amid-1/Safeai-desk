// frontend/src/features/chat/controller/operations/chatDirectoryActions.test.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatDirectoryActions.test.ts
// Регрессии каталога, создания, переименования и архивации чатов.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatDetails } from '../../api/chatApi'
import { archiveChat, createChat, getChats, updateChatTitle } from '../../api/chatApi'
import { chat, IDS, slice } from '../../../../test/fixtures/chatApiFixtures'
import { createDirectoryActions } from './chatDirectoryActions'

vi.mock('../../api/chatApi', async importOriginal => ({
    ...await importOriginal<typeof import('../../api/chatApi')>(),
    archiveChat: vi.fn(), createChat: vi.fn(),
    getChats: vi.fn(), updateChatTitle: vi.fn(), getChatById: vi.fn(),
}))

const chatsMock = vi.mocked(getChats)
const createMock = vi.mocked(createChat)
const archiveMock = vi.mocked(archiveChat)
const updateTitleMock = vi.mocked(updateChatTitle)

function mount(overrides: Partial<Parameters<typeof createDirectoryActions>[0]> = {}) {
    let chats = [chat({ title: 'Рабочий чат' })]
    let activeChat: ChatDetails | null = { ...chats[0]!, messages: [] }
    let listError = ''
    let creating = false
    const openChat = vi.fn(async () => undefined)
    const setChats = (next: typeof chats | ((previous: typeof chats) => typeof chats)) => {
        chats = typeof next === 'function' ? next(chats) : next
    }
    const setActiveChat = (next: ChatDetails | null | ((previous: ChatDetails | null) => ChatDetails | null)) => {
        activeChat = typeof next === 'function' ? next(activeChat) : next
    }
    const deps: Parameters<typeof createDirectoryActions>[0] = {
        chats, activeChat, pendingTurns: {}, moreChatsLoading: false,
        chatToRename: chats[0]!, renamingChatId: null, archivingChatId: null,
        chatPage: 0, chatHasNext: true,
        openRequestSequenceRef: { current: 0 },
        historyRequestSequenceRef: { current: 0 },
        openControllerRef: { current: null },
        historyControllerRef: { current: null },
        createInFlightRef: { current: false },
        pendingTurnsRef: { current: {} },
        activeChatRef: { current: activeChat },
        scrollIntentRef: { current: null },
        setChats,
        setActiveChat,
        setDrafts: vi.fn(),
        setListError(next) { listError = typeof next === 'function' ? next(listError) : next },
        setMoreChatsLoading: vi.fn(),
        setChatCreating(next) { creating = typeof next === 'function' ? next(creating) : next },
        setChatToArchive: vi.fn(),
        setRenamingChatId: vi.fn(),
        setArchivingChatId: vi.fn(),
        setOpeningChatId: vi.fn(),
        setHistoryLoading: vi.fn(),
        setChatPage: vi.fn(),
        setChatHasNext: vi.fn(),
        setHistoryPage: vi.fn(),
        setHistoryHasNext: vi.fn(),
        chatPageSize: 50,
        openChat,
        ...overrides,
    }
    const actions = createDirectoryActions(deps)
    return { ...actions, deps, openChat,
        get chats() { return chats }, get activeChat() { return activeChat },
        get error() { return listError }, get creating() { return creating } }
}

beforeEach(() => {
    vi.clearAllMocks()
    createMock.mockResolvedValue(chat({ title: 'Новый чат' }))
    archiveMock.mockResolvedValue(undefined)
    updateTitleMock.mockResolvedValue(chat({ title: 'Переименован' }))
})

describe('Каталог чатов', () => {
    it('запрашивает следующую страницу и не дублирует уже известные ID', async () => {
        chatsMock.mockResolvedValue(slice([chat(), chat({ id: 'another' })], 1))
        const h = mount()
        await h.loadMoreChats()
        expect(chatsMock).toHaveBeenCalledWith(1, 50)
        expect(new Set(h.chats.map(c => c.id)).size).toBe(2)
    })

    it('не загружает больше, когда сервер сообщил hasNext=false', async () => {
        const h = mount({ chatHasNext: false })
        await h.loadMoreChats()
        expect(chatsMock).not.toHaveBeenCalled()
    })

    it('не создаёт повторный пустой «Новый чат»', async () => {
        const h = mount({ activeChat: { ...chat(), messages: [] } })
        await h.handleCreateChat()
        expect(createMock).not.toHaveBeenCalled()
    })

    it('создаёт новый чат и показывает ответ backend', async () => {
        const h = mount()
        await h.handleCreateChat()
        expect(createMock).toHaveBeenCalledTimes(1)
        expect(h.activeChat?.title).toBe('Новый чат')
        expect(h.creating).toBe(false)
    })

    it('обновляет заголовок активного и списочного чатов', async () => {
        const h = mount()
        await h.handleRenameChat('Переименован')
        expect(updateTitleMock).toHaveBeenCalledWith(IDS.chat, 'Переименован')
        expect(h.activeChat?.title).toBe('Переименован')
        expect(h.chats[0]?.title).toBe('Переименован')
    })

    it('запрещает архивацию во время исходной pending-операции', async () => {
        const h = mount({ pendingTurnsRef: { current: { [IDS.chat]: {
            chatId: IDS.chat, clientRequestId: IDS.request,
            optimisticMessageId: `pending-${IDS.request}`,
            content: 'hello', status: 'SENDING', error: null,
            retryAfterUntil: null, knowledgeBaseId: null, knowledgeMode: 'GENERAL',
        } } } })
        await h.handleArchiveChat(chat())
        expect(archiveMock).not.toHaveBeenCalled()
    })

    it('архивирует чат через backend и удаляет его из списка', async () => {
        const h = mount()
        await h.handleArchiveChat(chat())
        expect(archiveMock).toHaveBeenCalledWith(IDS.chat)
        expect(h.chats).toHaveLength(0)
        expect(h.activeChat).toBeNull()
    })
})

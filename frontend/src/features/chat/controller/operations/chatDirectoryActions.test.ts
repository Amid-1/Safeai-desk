// frontend/src/features/chat/controller/operations/chatDirectoryActions.test.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatDirectoryActions.test.ts
// Регрессии каталога, создания, переименования и архивации чатов.
// ============================================================
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatDetails } from '../../api/chatApi'
import { archiveChat, assignAutomaticChatTitle, createChat, getChats, updateChatTitle } from '../../api/chatApi'
import { chat, IDS, slice } from '../../../../test/fixtures/chatApiFixtures'
import { applyReactStateUpdate } from '../../../../test/fixtures/applyReactStateUpdate'
import { createPendingTurn } from '../../lib/chatPage.helpers'
import { createDirectoryActions } from './chatDirectoryActions'

vi.mock('../../api/chatApi', async importOriginal => ({
    ...await importOriginal<typeof import('../../api/chatApi')>(),
    archiveChat: vi.fn(), assignAutomaticChatTitle: vi.fn(), createChat: vi.fn(),
    getChats: vi.fn(), updateChatTitle: vi.fn(), getChatById: vi.fn(),
}))

const chatsMock = vi.mocked(getChats)
const createMock = vi.mocked(createChat)
const archiveMock = vi.mocked(archiveChat)
const updateTitleMock = vi.mocked(updateChatTitle)
const autoTitleMock = vi.mocked(assignAutomaticChatTitle)

function mount(overrides: Partial<Parameters<typeof createDirectoryActions>[0]> = {}) {
    let chats = [chat({ title: 'Рабочий чат' })]
    let activeChat: ChatDetails | null = { ...chats[0]!, messages: [] }
    let listError = ''
    let creating = false
    const openChat = vi.fn(async () => undefined)
    const setChats = (next: typeof chats | ((previous: typeof chats) => typeof chats)) => {
        chats = applyReactStateUpdate(chats, next)
    }
    const setActiveChat = (next: ChatDetails | null | ((previous: ChatDetails | null) => ChatDetails | null)) => {
        activeChat = applyReactStateUpdate(activeChat, next)
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
        setListError(next) { listError = applyReactStateUpdate(listError, next) },
        setMoreChatsLoading: vi.fn(),
        setChatCreating(next) { creating = applyReactStateUpdate(creating, next) },
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
    autoTitleMock.mockResolvedValue(chat({ title: 'Составить план задач' }))
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

    it('создаёт чат с явным названием даже при открытом пустом чате', async () => {
        const h = mount({ activeChat: { ...chat(), messages: [] } })
        createMock.mockResolvedValueOnce({ ...chat(), title: 'План работ' })
        expect(await h.handleCreateChat('План работ')).toBeNull()
        expect(createMock).toHaveBeenCalledWith('План работ')
        expect(h.activeChat?.title).toBe('План работ')
    })

    it('обновляет заголовок активного и списочного чатов', async () => {
        const h = mount()
        await h.handleRenameChat('Переименован')
        expect(updateTitleMock).toHaveBeenCalledWith(IDS.chat, 'Переименован')
        expect(h.activeChat?.title).toBe('Переименован')
        expect(h.chats[0]?.title).toBe('Переименован')
    })

    it('назначает название через backend, не подменяя его локальным заголовком', async () => {
        const h = mount({ activeChat: { ...chat({ title: 'Новый чат' }), messages: [] } })
        await h.ensureAutomaticChatTitle(IDS.chat, 'Составить план задач')
        expect(autoTitleMock).toHaveBeenCalledWith(IDS.chat, 'Составить план задач')
        expect(h.chats[0]?.title).toBe('Рабочий чат')
        expect(h.activeChat?.title).toBe('Составить план задач')
    })

    it('запрещает архивацию во время исходной pending-операции', async () => {
        const h = mount({
            pendingTurnsRef: {
                current: { [IDS.chat]: createPendingTurn(IDS.chat, 'hello', IDS.request) },
            },
        })
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

    it('при автоназвании не перезаписывает ранее заданный заголовок', async () => {
        const h = mount()
        await h.ensureAutomaticChatTitle(IDS.chat, 'Тема нового разговора')
        expect(autoTitleMock).toHaveBeenCalledWith(IDS.chat, 'Тема нового разговора')
        expect(h.chats[0]?.title).toBe('Рабочий чат')
        expect(h.activeChat?.title).toBe('Рабочий чат')
    })

    it('ошибка назначения автоназвания не прерывает пользовательскую операцию', async () => {
        autoTitleMock.mockRejectedValueOnce(new Error('metadata unavailable'))
        const h = mount()
        await expect(h.ensureAutomaticChatTitle(IDS.chat, 'Тема')).resolves.toBeUndefined()
        expect(h.chats[0]?.title).toBe('Рабочий чат')
    })
})

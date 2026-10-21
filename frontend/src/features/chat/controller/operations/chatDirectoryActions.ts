// frontend/src/features/chat/controller/operations/chatDirectoryActions.ts
// ============================================================
// frontend/src/features/chat/controller/operations/chatDirectoryActions.ts
// Операции контроллера чата. Исходные сценарии сохранены.
// ============================================================
import { archiveChat, createChat, getChatById, getChats, updateChatTitle } from '../../api/chatApi'
import type { Chat } from '../../api/chatApi'
import { getApiErrorMessage } from '../../../../shared/api/http'
import { mergeChats } from '../../lib/chatPage.helpers'
import type { ChatControllerDependencies } from './chatControllerTypes'

export function createDirectoryActions(deps: Pick<ChatControllerDependencies,
    | 'chats'
    | 'activeChat'
    | 'pendingTurns'
    | 'moreChatsLoading'
    | 'chatToRename'
    | 'renamingChatId'
    | 'archivingChatId'
    | 'chatPage'
    | 'chatHasNext'
    | 'openRequestSequenceRef'
    | 'historyRequestSequenceRef'
    | 'openControllerRef'
    | 'historyControllerRef'
    | 'createInFlightRef'
    | 'pendingTurnsRef'
    | 'activeChatRef'
    | 'scrollIntentRef'
    | 'setChats'
    | 'setActiveChat'
    | 'setDrafts'
    | 'setListError'
    | 'setMoreChatsLoading'
    | 'setChatCreating'
    | 'setChatToArchive'
    | 'setRenamingChatId'
    | 'setArchivingChatId'
    | 'setOpeningChatId'
    | 'setHistoryLoading'
    | 'setChatPage'
    | 'setChatHasNext'
    | 'setHistoryPage'
    | 'setHistoryHasNext'
    | 'chatPageSize'
    | 'openChat'
>) {
    const { chats, activeChat, pendingTurns, moreChatsLoading, chatToRename, renamingChatId, archivingChatId, chatPage, chatHasNext, openRequestSequenceRef, historyRequestSequenceRef, openControllerRef, historyControllerRef, createInFlightRef, pendingTurnsRef, activeChatRef, scrollIntentRef, setChats, setActiveChat, setDrafts, setListError, setMoreChatsLoading, setChatCreating, setChatToArchive, setRenamingChatId, setArchivingChatId, setOpeningChatId, setHistoryLoading, setChatPage, setChatHasNext, setHistoryPage, setHistoryHasNext, chatPageSize, openChat } = deps

    async function loadMoreChats() {
        if (
            moreChatsLoading
            || !chatHasNext
        ) {
            return
        }

        const nextPage = chatPage + 1

        setMoreChatsLoading(true)
        setListError('')

        try {
            const response = await getChats(
                nextPage,
                chatPageSize,
            )

            setChats((current) =>
                mergeChats(
                    current,
                    response.content,
                ),
            )
            setChatPage(response.page)
            setChatHasNext(response.hasNext)
        } catch (error) {
            setListError(
                getApiErrorMessage(
                    error,
                    'Не удалось загрузить дополнительные чаты.',
                ),
            )
        } finally {
            setMoreChatsLoading(false)
        }
    }

    async function renameChatById(chatId: string, title: string): Promise<void> {
        const updated = await updateChatTitle(chatId, title)
        setChats((items) => items.map((item) =>
            item.id === chatId ? updated : item,
        ))
        setActiveChat((current) => current?.id === chatId
            ? { ...current, ...updated }
            : current,
        )
    }

    async function handleRenameChat(title: string): Promise<void> {
        const chatId = activeChatRef.current?.id
        if (!chatId) {
            throw new Error('Чат не выбран')
        }
        await renameChatById(chatId, title)
    }

    async function handleRenameFromHistory(title: string): Promise<void> {
        const chatId = chatToRename?.id
        if (!chatId || renamingChatId) {
            throw new Error('Переименование чата недоступно')
        }
        setRenamingChatId(chatId)
        try {
            await renameChatById(chatId, title)
        } finally {
            setRenamingChatId(null)
        }
    }

    async function refreshChatTitle(chatId: string): Promise<void> {
        // Источник названия чата — backend; клиент не должен придумывать его сам.
        try {
            const serverChat = await getChatById(chatId)
            setChats((items) => items.map((item) =>
                item.id === chatId
                    ? { ...item, title: serverChat.title, updatedAt: serverChat.updatedAt }
                    : item,
            ))
            setActiveChat((current) => current?.id === chatId
                ? { ...current, title: serverChat.title, updatedAt: serverChat.updatedAt }
                : current,
            )
        } catch {
            // Сообщение уже сохранено сервером. Следующая загрузка исправит
            // заголовок; повторная отправка сообщения ради UI запрещена.
        }
    }

    async function handleCreateChat() {
        if (createInFlightRef.current) {
            return
        }

        // Не плодим пустые "Новый чат" при повторных кликах после
        // сетевых/контрактных ошибок. Пользователь уже находится в пустом чате.
        if (
            activeChat
            && activeChat.title === 'Новый чат'
            && activeChat.messages.length === 0
            && !pendingTurns[activeChat.id]
        ) {
            return
        }

        createInFlightRef.current = true
        setChatCreating(true)
        setListError('')

        openRequestSequenceRef.current += 1
        historyRequestSequenceRef.current += 1

        openControllerRef.current?.abort()
        historyControllerRef.current?.abort()

        setOpeningChatId(null)
        setHistoryLoading(false)

        try {
            const chat = await createChat()

            setChats((current) => [
                chat,
                ...current.filter(
                    (item) => item.id !== chat.id,
                ),
            ])

            scrollIntentRef.current = {
                type: 'BOTTOM',
            }

            setActiveChat({
                ...chat,
                messages: [],
            })
            setHistoryPage(1)
            setHistoryHasNext(false)
        } catch (error) {
            setListError(
                getApiErrorMessage(
                    error,
                    'Не удалось создать чат.',
                ),
            )
        } finally {
            createInFlightRef.current = false
            setChatCreating(false)
        }
    }

    async function handleArchiveChat(chat: Chat) {
        if (archivingChatId || pendingTurnsRef.current[chat.id]) {
            return
        }

        setArchivingChatId(chat.id)
        setListError('')

        try {
            await archiveChat(chat.id)

            const remainingChats = chats.filter(
                (item) => item.id !== chat.id,
            )

            setChats(remainingChats)
            setDrafts((current) => {
                const next = { ...current }
                delete next[chat.id]
                return next
            })
            setChatToArchive(null)

            if (activeChatRef.current?.id === chat.id) {
                setActiveChat(null)
                const nextChat = remainingChats[0]
                if (nextChat) {
                    await openChat(nextChat.id)
                }
            }
        } catch (error) {
            setListError(
                getApiErrorMessage(
                    error,
                    'Не удалось убрать чат из списка.',
                ),
            )
            throw error
        } finally {
            setArchivingChatId(null)
        }
    }

    return { loadMoreChats, handleRenameChat, handleRenameFromHistory, refreshChatTitle, handleCreateChat, handleArchiveChat }
}

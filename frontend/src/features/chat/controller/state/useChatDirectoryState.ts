// frontend/src/features/chat/controller/state/useChatDirectoryState.ts
// Разделение независимых состояний списка, действий и пагинации чатов.
import { useState } from 'react'
import type { Chat } from '../../api/chatApi'

function useChatListState() {
    const [chats, setChats] = useState<Chat[]>([])
    const [listError, setListError] = useState('')
    const [chatsLoading, setChatsLoading] = useState(true)
    const [moreChatsLoading, setMoreChatsLoading] = useState(false)
    const [chatPage, setChatPage] = useState(0)
    const [chatHasNext, setChatHasNext] = useState(false)

    return {
        chats, setChats, listError, setListError,
        chatsLoading, setChatsLoading,
        moreChatsLoading, setMoreChatsLoading,
        chatPage, setChatPage, chatHasNext, setChatHasNext,
    }
}

function useChatManagementState() {
    const [chatCreating, setChatCreating] = useState(false)
    const [chatToArchive, setChatToArchive] = useState<Chat | null>(null)
    const [chatToRename, setChatToRename] = useState<Chat | null>(null)
    const [renamingChatId, setRenamingChatId] = useState<string | null>(null)
    const [archivingChatId, setArchivingChatId] = useState<string | null>(null)

    return {
        chatCreating, setChatCreating,
        chatToArchive, setChatToArchive, chatToRename, setChatToRename,
        renamingChatId, setRenamingChatId,
        archivingChatId, setArchivingChatId,
    }
}

function useChatNavigationState() {
    const [openingChatId, setOpeningChatId] = useState<string | null>(null)
    const [historyLoading, setHistoryLoading] = useState(false)
    const [historyPage, setHistoryPage] = useState(1)
    const [historyHasNext, setHistoryHasNext] = useState(false)
    const [reloadToken, setReloadToken] = useState(0)

    return {
        openingChatId, setOpeningChatId, historyLoading, setHistoryLoading,
        historyPage, setHistoryPage, historyHasNext, setHistoryHasNext,
        reloadToken, setReloadToken,
    }
}

export function useChatDirectoryState() {
    // Вызовы React-хуков остаются безусловными; их порядок фиксирован.
    const list = useChatListState()
    const management = useChatManagementState()
    const navigation = useChatNavigationState()

    return { ...list, ...management, ...navigation }
}

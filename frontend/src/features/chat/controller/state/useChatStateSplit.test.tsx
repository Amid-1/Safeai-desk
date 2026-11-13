// frontend/src/features/chat/controller/state/useChatStateSplit.test.tsx
// Проверка контракта состояний и стабильности refs после декомпозиции.
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ChatDetails } from '../../api/chatApi'
import type { PendingTurn } from '../../lib/chatPage.helpers'
import { useChatDirectoryState } from './useChatDirectoryState'
import { useChatRequestRefs } from './useChatRequestRefs'

describe('chat state composition', () => {
    it('сохранила начальные значения и все методы списка чатов', () => {
        const { result } = renderHook(() => useChatDirectoryState())
        expect(Object.keys(result.current).sort()).toEqual([
            'chats', 'setChats', 'listError', 'setListError',
            'chatsLoading', 'setChatsLoading', 'moreChatsLoading', 'setMoreChatsLoading',
            'chatCreating', 'setChatCreating', 'chatToArchive', 'setChatToArchive',
            'chatToRename', 'setChatToRename', 'renamingChatId', 'setRenamingChatId',
            'archivingChatId', 'setArchivingChatId', 'openingChatId', 'setOpeningChatId',
            'historyLoading', 'setHistoryLoading', 'chatPage', 'setChatPage',
            'chatHasNext', 'setChatHasNext', 'historyPage', 'setHistoryPage',
            'historyHasNext', 'setHistoryHasNext', 'reloadToken', 'setReloadToken',
        ].sort())
        expect(result.current.chats).toEqual([])
        expect(result.current.chatsLoading).toBe(true)
        expect(result.current.chatPage).toBe(0)
        expect(result.current.historyPage).toBe(1)
        expect(result.current.chatToArchive).toBeNull()

        act(() => result.current.setChatPage(previous => previous + 1))
        expect(result.current.chatPage).toBe(1)
    })

    it('сохранила refs между render и обновила ссылки после смены props', () => {
        const firstPending: Record<string, PendingTurn> = {}
        const chat = { id: 'a' } as ChatDetails
        const secondPending: Record<string, PendingTurn> = {}
        const { result, rerender } = renderHook(
            ({ active, pending }) => useChatRequestRefs(active, pending),
            { initialProps: { active: null as ChatDetails | null, pending: firstPending } },
        )
        expect(Object.keys(result.current).sort()).toEqual([
            'messagesEndRef', 'messagesContainerRef', 'listRequestSequenceRef',
            'openRequestSequenceRef', 'historyRequestSequenceRef', 'listControllerRef',
            'openControllerRef', 'historyControllerRef', 'reconciliationControllersRef',
            'createInFlightRef', 'sendInFlightRef', 'pendingTurnsRef',
            'activeChatRef', 'scrollIntentRef',
        ].sort())
        const sendLock = result.current.sendInFlightRef
        const cancellation = result.current.reconciliationControllersRef
        const listAbort = result.current.listControllerRef
        act(() => sendLock.current.add('request-1'))

        rerender({ active: chat, pending: secondPending })
        expect(result.current.sendInFlightRef).toBe(sendLock)
        expect(result.current.sendInFlightRef.current.has('request-1')).toBe(true)
        expect(result.current.reconciliationControllersRef).toBe(cancellation)
        expect(result.current.listControllerRef).toBe(listAbort)
        expect(result.current.activeChatRef.current).toBe(chat)
        expect(result.current.pendingTurnsRef.current).toBe(secondPending)
    })
})

// frontend/src/features/chat/controller/ChatArchiveModal.test.tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/http'
import { chat, userMessage, assistantMessage, IDS, AT } from '../../../test/fixtures/chatApiFixtures'
import type { Chat, ChatSliceResponse } from '../api/chatApi'
import {
    getArchivedChats, getArchivedChatDetails, restoreChat,
    getChatErasureCapability, getChatErasureOperation, listChatErasureOperations,
    requestChatContentErasure,
} from '../api/chat/chatArchiveClient'
import type { ChatErasureOperation } from '../api/chat/chatArchiveClient'
import ChatArchiveModal from './ChatArchiveModal'

const auth = vi.hoisted(() => ({ roles: ['ADMIN'] }))
vi.mock('../../auth/state/useAuth', () => ({
    useAuth: () => ({ currentUser: { roles: auth.roles } }),
}))
vi.mock('../api/chat/chatArchiveClient')
vi.mock('../../../shared/lib/secureUuid', () => ({
    createSecureUuid: () => 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
}))

const archived = chat({ title: 'Архивный чат' })
const other = chat({ id: IDS.turn, title: 'Другой чат' })
const detail = { ...archived, messages: [userMessage(), assistantMessage()] }
const capability = { allowed: true, reason: 'ENABLED', version: 5 }
const operation: ChatErasureOperation = {
    chatId: archived.id, operationId: IDS.operation, state: 'ERASING',
    createdAt: AT, completedAt: null, lastErrorCode: null,
}

function page(content: Chat[], index = 0, hasNext = false): ChatSliceResponse<Chat> {
    return { content, page: index, size: 50, first: index === 0, last: !hasNext,
        hasNext, hasPrevious: index > 0 }
}

function status(code: number) {
    return new ApiError('request failed', {}, code)
}

function mount() {
    const onChanged = vi.fn()
    const onClose = vi.fn()
    render(<ChatArchiveModal onClose={onClose} onChanged={onChanged} />)
    return { onChanged, onClose }
}

async function open() {
    fireEvent.click(await screen.findByRole('button', { name: archived.title }))
    await screen.findByRole('heading', { name: archived.title })
}

async function inspect() {
    fireEvent.click(screen.getByRole('button', { name: /Стереть содержимое/ }))
}

async function confirm() {
    const title = await screen.findByLabelText('Введите точное название чата')
    const phrase = screen.getByLabelText('Введите УДАЛИТЬ НАВСЕГДА')
    const button = screen.getByRole('button', { name: 'Подтвердить стирание' })
    expect(button).toBeDisabled()
    fireEvent.change(title, { target: { value: 'неверное название' } })
    fireEvent.change(phrase, { target: { value: 'УДАЛИТЬ НАВСЕГДА' } })
    expect(button).toBeDisabled()
    fireEvent.change(title, { target: { value: archived.title } })
    expect(button).toBeEnabled()
    fireEvent.click(button)
}

beforeEach(() => {
    auth.roles = ['ADMIN']
    vi.mocked(getArchivedChats).mockResolvedValue(page([archived]))
    vi.mocked(getArchivedChatDetails).mockResolvedValue(detail)
    vi.mocked(listChatErasureOperations).mockResolvedValue([])
    vi.mocked(getChatErasureOperation).mockRejectedValue(status(404))
    vi.mocked(getChatErasureCapability).mockResolvedValue(capability)
    vi.mocked(restoreChat).mockResolvedValue(undefined)
    vi.mocked(requestChatContentErasure).mockResolvedValue(operation)
})

describe('ChatArchiveModal', () => {
    it('allows a non-admin to read and restore but not erase or query erasure history', async () => {
        auth.roles = ['USER']
        const { onChanged } = mount()
        await open()
        expect(screen.getByText('hello')).toBeInTheDocument()
        expect(screen.getByText('answer')).toBeInTheDocument()
        expect(screen.queryByRole('button', { name: /Стереть содержимое/ })).not.toBeInTheDocument()
        expect(listChatErasureOperations).not.toHaveBeenCalled()
        vi.mocked(getArchivedChats).mockResolvedValue(page([]))
        fireEvent.click(screen.getByRole('button', { name: 'Восстановить чат' }))
        await screen.findByText('Архив пуст.')
        expect(restoreChat).toHaveBeenCalledWith(archived.id)
        expect(onChanged).toHaveBeenCalledTimes(1)
        expect(requestChatContentErasure).not.toHaveBeenCalled()
    })

    it('loads another page without duplicating the existing chat', async () => {
        vi.mocked(getArchivedChats).mockResolvedValueOnce(page([archived], 0, true))
            .mockResolvedValueOnce(page([archived, other], 1))
        mount()
        fireEvent.click(await screen.findByRole('button', { name: 'Загрузить ещё' }))
        await screen.findByRole('button', { name: other.title })
        expect(screen.getAllByRole('button', { name: archived.title })).toHaveLength(1)
        expect(getArchivedChats).toHaveBeenLastCalledWith(1, 50)
        expect(screen.queryByRole('button', { name: 'Загрузить ещё' })).not.toBeInTheDocument()
    })

    it('only treats a forbidden erasure history as optional', async () => {
        vi.mocked(listChatErasureOperations).mockRejectedValue(status(403))
        mount()
        await screen.findByRole('button', { name: archived.title })
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('surfaces an unexpected archive loading failure', async () => {
        vi.mocked(getArchivedChats).mockRejectedValue(status(503))
        mount()
        expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось загрузить архив.')
    })

    it('blocks erasure when the server denies the capability', async () => {
        vi.mocked(getChatErasureCapability).mockResolvedValue({ ...capability, allowed: false, reason: 'RETENTION' })
        mount()
        await open()
        await inspect()
        expect(await screen.findByRole('alert')).toHaveTextContent('Удаление недоступно: RETENTION.')
        expect(screen.queryByLabelText('Введите точное название чата')).not.toBeInTheDocument()
        expect(requestChatContentErasure).not.toHaveBeenCalled()
    })

    it('fails closed if checking an existing operation returns an unexpected error', async () => {
        vi.mocked(getChatErasureOperation).mockRejectedValue(status(503))
        mount()
        await open()
        await inspect()
        expect(await screen.findByRole('alert')).toHaveTextContent('Удаление заблокировано.')
        expect(getChatErasureCapability).not.toHaveBeenCalled()
        expect(requestChatContentErasure).not.toHaveBeenCalled()
    })

    it.each(['ERASING', 'NEEDS_REVIEW', 'BLOCKED_BY_RETENTION', 'ERASED'] as const)(
        'reconciles an existing %s operation without creating a new erasure', async state => {
            vi.mocked(getChatErasureOperation).mockResolvedValue({ ...operation, state })
            mount()
            await open()
            await inspect()
            expect(await screen.findByRole('status')).toHaveTextContent(`Состояние стирания: ${state}`)
            expect(screen.queryByText('hello')).not.toBeInTheDocument()
            expect(getChatErasureCapability).not.toHaveBeenCalled()
            expect(requestChatContentErasure).not.toHaveBeenCalled()
        },
    )

    it('allows a fresh confirmation after a cancelled operation but sends nothing on cancel', async () => {
        vi.mocked(getChatErasureOperation).mockResolvedValue({ ...operation, state: 'CANCELLED' })
        mount()
        await open()
        await inspect()
        await screen.findByLabelText('Введите УДАЛИТЬ НАВСЕГДА')
        expect(screen.getByRole('button', { name: 'Восстановить чат' })).toBeDisabled()
        fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
        expect(screen.queryByLabelText('Введите УДАЛИТЬ НАВСЕГДА')).not.toBeInTheDocument()
        expect(requestChatContentErasure).not.toHaveBeenCalled()
        expect(screen.getByText('hello')).toBeInTheDocument()
    })

    it('rejects a stale version before issuing a destructive request', async () => {
        vi.mocked(getChatErasureCapability).mockResolvedValueOnce(capability)
            .mockResolvedValueOnce({ ...capability, version: 6 })
        mount()
        await open()
        await inspect()
        await confirm()
        expect(await screen.findByRole('alert')).toHaveTextContent('Чат изменился')
        expect(requestChatContentErasure).not.toHaveBeenCalled()
        expect(screen.queryByLabelText('Введите точное название чата')).not.toBeInTheDocument()
    })

    it('sends erasure only after exact confirmation, then hides content and shows acceptance', async () => {
        const { onChanged } = mount()
        await open()
        await inspect()
        await confirm()
        expect(await screen.findByRole('status')).toHaveTextContent('ERASING')
        expect(requestChatContentErasure).toHaveBeenCalledExactlyOnceWith(archived.id, archived.title, 5, IDS.request)
        expect(screen.queryByText('hello')).not.toBeInTheDocument()
        expect(screen.queryByRole('button', { name: 'Восстановить чат' })).not.toBeInTheDocument()
        await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1))
    })

    it('reconciles an ambiguous response instead of creating a second destructive request', async () => {
        vi.mocked(requestChatContentErasure).mockRejectedValue(status(503))
        vi.mocked(getChatErasureOperation).mockRejectedValueOnce(status(404))
            .mockResolvedValueOnce({ ...operation, state: 'ERASED' })
        mount()
        await open()
        await inspect()
        await confirm()
        expect(await screen.findByRole('status')).toHaveTextContent('ERASED')
        expect(requestChatContentErasure).toHaveBeenCalledTimes(1)
        expect(getChatErasureOperation).toHaveBeenCalledTimes(2)
        expect(screen.queryByText('hello')).not.toBeInTheDocument()
    })
})

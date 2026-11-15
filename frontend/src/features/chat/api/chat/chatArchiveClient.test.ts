// frontend/src/features/chat/api/chat/chatArchiveClient.test.ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from '../../../../shared/api/http'
import {
    getChatErasureCapability, getChatErasureOperation, requestChatContentErasure,
    getArchivedChats, restoreChat,
} from './chatArchiveClient'

vi.mock('../../../../shared/api/http', async importOriginal => ({
    ...await importOriginal<typeof import('../../../../shared/api/http')>(),
    apiRequest: vi.fn(),
}))
const request = vi.mocked(apiRequest)
const ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const operation = {
    operationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    chatId: ID, state: 'ERASING', createdAt: '2026-11-14T00:00:00Z',
    completedAt: null, lastErrorCode: null,
} as const

beforeEach(() => { vi.resetAllMocks() })

describe('Безопасный архив и стирание чатов', () => {
    it('получает архив по отдельному маршруту без модификации чата', async () => {
        request.mockResolvedValueOnce({
            content: [], page: 0, size: 50, first: true, last: true,
            hasNext: false, hasPrevious: false,
        })
        expect((await getArchivedChats()).content).toEqual([])
        expect(request).toHaveBeenCalledWith('/api/chats/archived?page=0&size=50',
            expect.objectContaining({ method: 'GET' }))
    })

    it('восстанавливает чат отдельным POST, не создавая сообщения', async () => {
        request.mockResolvedValueOnce(undefined)
        await restoreChat(ID)
        expect(request).toHaveBeenCalledWith(`/api/chats/${ID}/restore`, { method: 'POST' })
    })

    it('отказывает при недостоверной версии или capability', async () => {
        await expect(requestChatContentErasure(ID, 'title', -1, 'request-key-123'))
            .rejects.toThrow('Недостоверная версия чата.')
        expect(request).not.toHaveBeenCalled()
        request.mockResolvedValueOnce({ allowed: true, reason: 'ENABLED', version: -1 })
        await expect(getChatErasureCapability(ID)).rejects.toThrow('Недостоверный')
    })

    it('передаёт точную версию и повторяемый ключ при необратимом запросе', async () => {
        request.mockResolvedValueOnce(operation)
        const result = await requestChatContentErasure(ID, 'Sensitive topic', 5, 'request-key-123')
        expect(result).toEqual(operation)
        expect(request).toHaveBeenCalledWith(`/api/chats/${ID}/erasure`, {
            method: 'DELETE',
            headers: { 'If-Match': '"5"', 'Idempotency-Key': 'request-key-123' },
            json: { confirmationTitle: 'Sensitive topic', confirmationPhrase: 'УДАЛИТЬ НАВСЕГДА' },
        })
    })

    it('не принимает результат операции чужого чата', async () => {
        request.mockResolvedValueOnce({ ...operation, chatId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' })
        await expect(getChatErasureOperation(ID)).rejects.toThrow('несогласованную')
    })
})

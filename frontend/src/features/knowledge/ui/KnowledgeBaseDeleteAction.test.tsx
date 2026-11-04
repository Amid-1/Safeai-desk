// frontend/src/features/knowledge/ui/KnowledgeBaseDeleteAction.test.tsx
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {ApiError} from '../../../shared/api/http'
import type {KnowledgeBase} from '../../../entities/knowledge/api/knowledgeApi'
import {acceptBaseDeletion, baseDeletionCapability, getBaseDeletion, listBaseDeletions}
    from '../../../entities/knowledge/api/knowledgeBaseDeletionApi'
import {KnowledgeBaseDeleteAction} from './KnowledgeBaseDeleteAction'

vi.mock('../../../entities/knowledge/api/knowledgeBaseDeletionApi', () => ({
    acceptBaseDeletion: vi.fn(),
    baseDeletionCapability: vi.fn(),
    getBaseDeletion: vi.fn(),
    listBaseDeletions: vi.fn(),
}))
vi.mock('../../../shared/lib/secureUuid', () => ({
    createSecureUuid: () => 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
}))

const base: KnowledgeBase = {
    id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    organizationId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    name: 'Закрытая база', description: null, visibility: 'MEMBERS',
    enabled: true, version: 3,
    createdByUserId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
    createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z',
}
const operation = {
    operationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    knowledgeBaseId: base.id, state: 'DELETING' as const,
    createdAt: '2026-10-28T00:00:00Z', completedAt: null, errorCode: null,
}

beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(baseDeletionCapability).mockResolvedValue(false)
    vi.mocked(listBaseDeletions).mockResolvedValue([])
    vi.mocked(getBaseDeletion).mockRejectedValue(new ApiError('Не найдено', {}, 404))
})

async function openConfirmation() {
    fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
    return screen.findByRole('textbox', {name: 'Название базы знаний'})
}

describe('KnowledgeBaseDeleteAction: безопасный жизненный цикл', () => {
    it('показывает информационное окно при выключенной функции, не вызывает DELETE', async () => {
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        expect(await screen.findByText('Удаление баз знаний пока отключено в настройках системы.'))
            .toBeInTheDocument()
        expect(screen.getByText('Ограничение безопасности')).toBeInTheDocument()
        expect(screen.queryByRole('textbox', {name: 'Название базы знаний'})).not.toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Понятно'})).toBeEnabled()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('не разрешает DELETE, пока не получена история операций', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(listBaseDeletions).mockRejectedValue(new Error('history unavailable'))
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        expect(await screen.findByText(/Новый запрос запрещён/)).toBeInTheDocument()
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('находит старую операцию через current, даже если она выпала из последних 50', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(listBaseDeletions).mockResolvedValue([])
        vi.mocked(getBaseDeletion).mockResolvedValue({...operation, state: 'NEEDS_REVIEW'})
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        expect(await screen.findByText('Требуется проверка администратором')).toBeInTheDocument()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('при неизвестной ошибке current закрывает доступ к новому DELETE', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(getBaseDeletion).mockRejectedValue(new Error('database unavailable'))
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        expect(await screen.findByText(/Не удалось сверить текущую операцию базы/)).toBeInTheDocument()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('не создаёт новую операцию при активном retention-блоке', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(listBaseDeletions).mockResolvedValue([{
            ...operation, state: 'BLOCKED_BY_RETENTION', errorCode: 'RETENTION',
        }])
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        expect(await screen.findByText('Удаление ограничено сроком хранения')).toBeInTheDocument()
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('сохраняет конфузаблы, версию и ключ; HTTP 202 показывает DELETING, не DELETED', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(acceptBaseDeletion).mockResolvedValue(operation)
        const accepted = vi.fn()
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={accepted}/>)
        const input = await openConfirmation()
        const button = screen.getByRole('button', {name: 'Подтвердить удаление базы'})
        expect(button).toBeDisabled()
        fireEvent.change(input, {target: {value: 'ЗАКРЫТАЯ БАЗА'}})
        expect(button).toBeEnabled()
        fireEvent.click(button)
        await waitFor(() => expect(acceptBaseDeletion).toHaveBeenCalledWith(
            base.id, 3, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'))
        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(screen.queryByText('Удаление завершено')).not.toBeInTheDocument()
        expect(accepted).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('button', {name: 'Закрыть'}))
        expect(accepted).toHaveBeenCalledTimes(1)
    })

    it('не разрешает новое удаление, если current принадлежит другой базе', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(getBaseDeletion).mockResolvedValue({
            ...operation, knowledgeBaseId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        })
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить базу…'}))
        expect(await screen.findByText(/Сервер вернул операцию другой базы/)).toBeInTheDocument()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('не принимает ответ DELETE с идентификатором другой базы', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(acceptBaseDeletion).mockResolvedValue({
            ...operation, knowledgeBaseId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        })
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        const input = await openConfirmation()
        fireEvent.change(input, {target: {value: base.name}})
        fireEvent.click(screen.getByRole('button', {name: 'Подтвердить удаление базы'}))
        expect(await screen.findByText(/Сервер вернул идентификатор другой базы/)).toBeInTheDocument()
        expect(acceptBaseDeletion).toHaveBeenCalledTimes(1)
        expect(screen.queryByRole('button', {name: 'Подтвердить удаление базы'})).not.toBeInTheDocument()
        expect(screen.queryByText('Удаление завершено')).not.toBeInTheDocument()
    })

    it('после потери ответа DELETE находит операцию через current при пустой истории', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(getBaseDeletion).mockReset()
        vi.mocked(getBaseDeletion)
            .mockRejectedValueOnce(new ApiError('Не найдено', {}, 404))
            .mockResolvedValue(operation)
        vi.mocked(acceptBaseDeletion).mockRejectedValue(new Error('Соединение оборвалось'))
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        const input = await openConfirmation()
        fireEvent.change(input, {target: {value: base.name}})
        fireEvent.click(screen.getByRole('button', {name: 'Подтвердить удаление базы'}))
        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(acceptBaseDeletion).toHaveBeenCalledTimes(1)
    })

    it('не принимает похожие символы из другого алфавита', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        render(<KnowledgeBaseDeleteAction base={{...base, name: 'X3'}} onAccepted={vi.fn()}/>)
        const input = await openConfirmation()
        fireEvent.change(input, {target: {value: 'х3'}})
        expect(screen.getByRole('button', {name: 'Подтвердить удаление базы'})).toBeDisabled()
        expect(acceptBaseDeletion).not.toHaveBeenCalled()
    })

    it('повторяет неопределённый запрос с прежним Idempotency-Key', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(acceptBaseDeletion).mockRejectedValueOnce(new Error('network lost'))
            .mockResolvedValueOnce(operation)
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        const input = await openConfirmation()
        fireEvent.change(input, {target: {value: base.name}})
        const button = screen.getByRole('button', {name: 'Подтвердить удаление базы'})
        fireEvent.click(button)
        await waitFor(() => expect(acceptBaseDeletion).toHaveBeenCalledTimes(1))
        await waitFor(() => expect(button).toBeEnabled())
        fireEvent.click(button)
        await waitFor(() => expect(acceptBaseDeletion).toHaveBeenCalledTimes(2))
        const calls = vi.mocked(acceptBaseDeletion).mock.calls
        expect(calls[0]?.[2]).toEqual(calls[1]?.[2])
        expect(calls[0]?.[1]).toEqual(calls[1]?.[1])
    })

    it('при найденной операции после потери ответа не посылает повторный DELETE', async () => {
        vi.mocked(baseDeletionCapability).mockResolvedValue(true)
        vi.mocked(acceptBaseDeletion).mockRejectedValue(new Error('connection broken'))
        vi.mocked(listBaseDeletions).mockResolvedValueOnce([]).mockResolvedValueOnce([operation])
        render(<KnowledgeBaseDeleteAction base={base} onAccepted={vi.fn()}/>)
        const input = await openConfirmation()
        fireEvent.change(input, {target: {value: base.name}})
        fireEvent.click(screen.getByRole('button', {name: 'Подтвердить удаление базы'}))
        expect(await screen.findByText('Удаление выполняется')).toBeInTheDocument()
        expect(acceptBaseDeletion).toHaveBeenCalledTimes(1)
    })
})

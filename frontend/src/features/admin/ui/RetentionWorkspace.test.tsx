// frontend/src/features/admin/ui/RetentionWorkspace.test.tsx
import { render, screen, within, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RetentionWorkspace } from './RetentionWorkspace'
import { loadRetention, saveRetention, type RetentionStatus } from '../api/retentionApi'

vi.mock('../api/retentionApi', () => ({loadRetention: vi.fn(), saveRetention: vi.fn()}))
const gate = {configured: true, workerInitialized: true}
const status: RetentionStatus = {chatAllowed: true, knowledgeAllowed: true, version: 4,
    chat: gate, document: gate, base: gate, userRetentionSeconds: 604800}

function mount() {
    return render(<MemoryRouter><RetentionWorkspace/></MemoryRouter>)
}

describe('Хранение и удаление', () => {
    beforeEach(() => {vi.resetAllMocks(); vi.mocked(loadRetention).mockResolvedValue(status)})

    it('сохраняет изменение только после подтверждения и с версией сервера', async () => {
        vi.mocked(saveRetention).mockResolvedValue({...status, chatAllowed: false, version: 5})
        mount()
        const pause = await screen.findAllByRole('button', {name: 'Приостановить новые удаления'})
        fireEvent.click(pause[0])
        expect(saveRetention).not.toHaveBeenCalled()
        fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {name: 'Сохранить политику'}))
        expect(await screen.findByText('Приостановлено')).toBeInTheDocument()
        expect(saveRetention).toHaveBeenCalledWith({chatAllowed: false, knowledgeAllowed: true, expectedVersion: 4})
    })

    it('не позволяет разрешить удаление без подготовки инфраструктуры', async () => {
        vi.mocked(loadRetention).mockResolvedValue({...status, chatAllowed: false,
            chat: {configured: false, workerInitialized: false}})
        mount()
        expect(await screen.findByText('Требуется подготовка сервера')).toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Разрешить новые удаления'})).toBeDisabled()
        expect(saveRetention).not.toHaveBeenCalled()
    })
    it('после неопределённого результата не отображает старую политику как актуальную', async () => {
        vi.mocked(saveRetention).mockRejectedValue(new Error('offline'))
        mount()
        fireEvent.click((await screen.findAllByRole('button', {name: 'Приостановить новые удаления'}))[0])
        fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {name: 'Сохранить политику'}))
        expect(await screen.findByText('Политика недоступна')).toBeInTheDocument()
        expect(screen.queryByRole('button', {name: 'Разрешить новые удаления'})).not.toBeInTheDocument()
    })
})

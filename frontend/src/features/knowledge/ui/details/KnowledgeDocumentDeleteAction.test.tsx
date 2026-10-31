// frontend/src/features/knowledge/ui/details/KnowledgeDocumentDeleteAction.test.tsx
// frontend/src/features/knowledge/ui/details/KnowledgeDocumentDeleteAction.test.tsx
import {fireEvent, render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import type {KnowledgeDocument} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import {KnowledgeDocumentDeleteAction} from './KnowledgeDocumentDeleteAction'

const document = {
    id: '11111111-1111-4111-8111-111111111111',
    knowledgeBaseId: '22222222-2222-4222-8222-222222222222',
    name: 'Тестовый документ', enabled: true, version: 1,
    currentVersionId: null, searchableVersionId: null,
    versionNumber: null, originalFilename: null, mediaType: null,
    sizeBytes: 0, status: 'PENDING',
    createdAt: '2026-10-31T00:00:00Z', updatedAt: '2026-10-31T00:00:00Z',
} as KnowledgeDocument

const disabled = 'Удаление документов отключено в настройках системы.'

describe('KnowledgeDocumentDeleteAction', () => {
    it('объясняет запрет и не передаёт документ в обработчик удаления', () => {
        const onDelete = vi.fn()
        render(<KnowledgeDocumentDeleteAction document={document} busy={false}
            canDelete={false} unavailableReason={disabled} onDelete={onDelete}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить…'}))
        expect(screen.getByText(disabled)).toBeInTheDocument()
        expect(screen.getByText('Чтобы воспользоваться этой функцией, её необходимо включить и проверить.'))
            .toBeInTheDocument()
        expect(screen.getByRole('dialog')).toBeInTheDocument()
        expect(onDelete).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('button', {name: 'Закрыть'}))
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('запрашивает подтверждение только после разрешения сервера', () => {
        const onDelete = vi.fn()
        render(<KnowledgeDocumentDeleteAction document={document} busy={false}
            canDelete={true} unavailableReason="" onDelete={onDelete}/>)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить…'}))
        expect(onDelete).toHaveBeenCalledTimes(1)
        expect(onDelete).toHaveBeenCalledWith(document)
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('блокирует действия во время выполнения другого запроса', () => {
        const onDelete = vi.fn()
        render(<KnowledgeDocumentDeleteAction document={document} busy={true}
            canDelete={true} unavailableReason="" onDelete={onDelete}/>)
        expect(screen.getByRole('button', {name: 'Удалить…'})).toBeDisabled()
        expect(onDelete).not.toHaveBeenCalled()
    })

    it('сохраняет действие удаления при непроверенной доступности, не отправляя запрос', () => {
        const onDelete = vi.fn()
        render(<KnowledgeDocumentDeleteAction document={document} busy={false}
            canDelete={false} unavailableReason="" onDelete={onDelete}/>)
        const button = screen.getByRole('button', {name: 'Удалить…'})
        expect(button).toBeEnabled()
        fireEvent.click(button)
        expect(screen.getByText('Удаление документа сейчас недоступно.')).toBeInTheDocument()
        expect(onDelete).not.toHaveBeenCalled()
    })
})

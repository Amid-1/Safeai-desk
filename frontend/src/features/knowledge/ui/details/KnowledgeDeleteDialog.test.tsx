// frontend/src/features/knowledge/ui/details/KnowledgeDeleteDialog.test.tsx
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import type {KnowledgeDocument} from '../../../../entities/knowledge/api/knowledgeDocumentApi'
import {KnowledgeDeleteDialog} from './KnowledgeDeleteDialog'

const document = {
    id: '11111111-1111-4111-8111-111111111111',
    knowledgeBaseId: '22222222-2222-4222-8222-222222222222',
    name: 'X3 Документ', enabled: true, version: 1,
    currentVersionId: null, searchableVersionId: null,
    versionNumber: null, originalFilename: null, mediaType: null,
    sizeBytes: 0, status: 'PENDING',
    createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z',
} as KnowledgeDocument

describe('KnowledgeDeleteDialog', () => {
    it('keeps label separate from the input and ignores case on matching characters', async () => {
        const onConfirm = vi.fn().mockResolvedValue(undefined)
        render(<KnowledgeDeleteDialog document={document} busy={false}
            onCancel={vi.fn()} onConfirm={onConfirm}/>)
        expect(screen.getByText(/После подтверждения система начнёт удаление/)).toBeInTheDocument()
        const input = screen.getByRole('textbox', {name: 'Название документа'})
        expect(input.previousElementSibling?.tagName).toBe('LABEL')
        const button = screen.getByRole('button', {name: 'Подтвердить удаление документа'})
        expect(button).toBeDisabled()
        fireEvent.change(input, {target: {value: 'x3 документ'}})
        expect(button).toBeEnabled()
        fireEvent.click(button)
        await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1))
    })

    it('never confuses a Cyrillic lookalike with a Latin letter', () => {
        render(<KnowledgeDeleteDialog document={document} busy={false}
            onCancel={vi.fn()} onConfirm={vi.fn()}/>)
        fireEvent.change(screen.getByRole('textbox', {name: 'Название документа'}),
            {target: {value: 'х3 документ'}})
        expect(screen.getByRole('button', {name: 'Подтвердить удаление документа'})).toBeDisabled()
    })

    it('shows a failed submission inside the modal instead of silently swallowing it', async () => {
        const onConfirm = vi.fn().mockRejectedValue(new Error('simulated failure'))
        render(<KnowledgeDeleteDialog document={document} busy={false}
            onCancel={vi.fn()} onConfirm={onConfirm}/>)
        fireEvent.change(screen.getByRole('textbox', {name: 'Название документа'}),
            {target: {value: document.name}})
        fireEvent.click(screen.getByRole('button', {name: 'Подтвердить удаление документа'}))
        await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
        expect(onConfirm).toHaveBeenCalledTimes(1)
    })
})

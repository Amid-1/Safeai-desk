// frontend/src/features/knowledge/ui/KnowledgeDeletionProgress.test.tsx
import {render, screen} from '@testing-library/react'
import {describe, expect, it} from 'vitest'
import {KnowledgeDeletionProgress} from './KnowledgeDeletionProgress'

const base = {
    operationId: '22222222-2222-4222-8222-222222222222',
    createdAt: '2026-10-29T12:00:00Z', completedAt: null,
}

describe('KnowledgeDeletionProgress', () => {
    it('DELETING не выдаёт псевдопроцент или ложное завершение', () => {
        render(<KnowledgeDeletionProgress operation={{...base, state: 'DELETING'}}/>)
        expect(screen.getByText('Удаление выполняется')).toBeInTheDocument()
        expect(screen.getByRole('progressbar')).not.toHaveAttribute('value')
        expect(screen.queryByText('Удаление завершено')).not.toBeInTheDocument()
    })
    it('NEEDS_REVIEW не предлагает повторную операцию', () => {
        render(<KnowledgeDeletionProgress operation={{...base, state: 'NEEDS_REVIEW'}}/>)
        expect(screen.getByText('Требуется проверка администратором')).toBeInTheDocument()
        expect(screen.queryByRole('button', {name: /повтор/i})).not.toBeInTheDocument()
    })
    it('BLOCKED_BY_RETENTION не скрывает диагностический код', () => {
        render(<KnowledgeDeletionProgress operation={{...base, state: 'BLOCKED_BY_RETENTION',
            errorCode: 'RETENTION_LOCK'}}/>)
        expect(screen.getByText('Удаление ограничено сроком хранения')).toBeInTheDocument()
        expect(screen.getByText('RETENTION_LOCK')).toBeInTheDocument()
    })
    it('DELETED появляется только из подтверждённого состояния API', () => {
        render(<KnowledgeDeletionProgress operation={{...base, state: 'DELETED',
            completedAt: '2026-10-29T12:02:00Z'}}/>)
        expect(screen.getByText('Удаление завершено')).toBeInTheDocument()
        expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    })
})

// frontend/src/features/knowledge/ui/KnowledgeBaseFormModal.test.tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/http'
import KnowledgeBaseFormModal from './KnowledgeBaseFormModal'

const initial = {
    name: 'Тестовая база',
    description: '',
    visibility: 'ORGANIZATION' as const,
    enabled: true,
}

function conflictError() {
    return new ApiError('Конфликт версий', {
        status: 409,
        error: 'CONFLICT',
        message: 'Конфликт версий',
    }, 409)
}

describe('KnowledgeBaseFormModal', () => {
    it('разъясняет VIEWER-доступ и сохранение истории после изменения видимости', () => {
        render(<KnowledgeBaseFormModal
            title="Настройки" submitText="Сохранить" initial={initial}
            busy={false} allowEnabled onClose={vi.fn()} onSubmit={vi.fn()}
        />)
        expect(screen.getByText(/Загрузка и управление требуют отдельно назначенных прав/))
            .toBeInTheDocument()
        expect(screen.getByText(/уже сохранённые сообщения чатов/i))
            .toBeInTheDocument()
        fireEvent.click(screen.getByRole('radio', { name: /Только приглашённые сотрудники/ }))
        expect(screen.getByText(/без приглашения больше не смогут/))
            .toBeInTheDocument()
    })

    it('при 409 блокирует повторную запись и предлагает загрузить актуальную версию', async () => {
        const onSubmit = vi.fn().mockRejectedValue(conflictError())
        const onReload = vi.fn().mockResolvedValue(undefined)
        render(<KnowledgeBaseFormModal
            title="Настройки" submitText="Сохранить" initial={initial}
            busy={false} allowEnabled onClose={vi.fn()}
            onSubmit={onSubmit} onReload={onReload}
        />)
        fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
        expect(await screen.findByText(/База знаний была изменена параллельно/))
            .toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
        expect(onSubmit).toHaveBeenCalledTimes(1)
        fireEvent.click(screen.getByRole('button', { name: 'Загрузить актуальные настройки' }))
        await waitFor(() => expect(onReload).toHaveBeenCalledTimes(1))
    })

    it('не принимает конфликт названия за конфликт optimistic version', async () => {
        const onSubmit = vi.fn().mockRejectedValue(new ApiError(
            'База знаний с таким названием уже существует',
            { status: 409, error: 'CONFLICT' },
            409,
        ))
        render(<KnowledgeBaseFormModal
            title="Настройки" submitText="Сохранить" initial={initial}
            busy={false} allowEnabled onClose={vi.fn()}
            onSubmit={onSubmit} onReload={vi.fn()}
        />)
        fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
        expect(await screen.findByText(/с таким названием уже существует/))
            .toBeInTheDocument()
        expect(screen.queryByRole('button', {
            name: 'Загрузить актуальные настройки',
        })).not.toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Сохранить' })).toBeEnabled()
    })

    it('не допускает двойную отправку, пока сервер не ответил', async () => {
        let finish: (() => void) | undefined
        const onSubmit = vi.fn().mockImplementation(
            () => new Promise<void>((resolve) => { finish = resolve }),
        )
        render(<KnowledgeBaseFormModal
            title="Настройки" submitText="Сохранить" initial={initial}
            busy={false} allowEnabled onClose={vi.fn()} onSubmit={onSubmit}
        />)
        fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
        expect(screen.getByRole('button', { name: 'Сохранение...' })).toBeDisabled()
        finish?.()
        await waitFor(() => expect(screen.getByRole('button', {
            name: 'Сохранить',
        })).toBeEnabled())
        expect(onSubmit).toHaveBeenCalledTimes(1)
    })
})

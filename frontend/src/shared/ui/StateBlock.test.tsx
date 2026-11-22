// frontend/src/shared/ui/StateBlock.test.tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EmptyState, ErrorState, LoadingState } from './StateBlock'

describe('Общие состояния интерфейса', () => {
    it('озвучивает загрузку без блокировки live region', () => {
        render(<LoadingState />)
        expect(screen.getByRole('status')).toHaveTextContent('Загрузка...')
        expect(screen.getByRole('status')).not.toHaveAttribute('aria-busy')
    })

    it('озвучивает ошибку и позволяет повторить действие', () => {
        const retry = vi.fn()
        render(<ErrorState message="Попробуйте ещё раз" action={<button onClick={retry}>Повторить</button>} />)
        expect(screen.getByRole('alert')).toHaveTextContent('Попробуйте ещё раз')
        fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
        expect(retry).toHaveBeenCalledOnce()
    })

    it('показывает пустое состояние как status, а не ошибку', () => {
        render(<EmptyState title="Нет записей" message="Измените фильтры" variant="inline" />)
        expect(screen.getByRole('status')).toHaveTextContent('Измените фильтры')
        expect(screen.getByRole('heading', { name: 'Нет записей' })).toBeVisible()
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })
})

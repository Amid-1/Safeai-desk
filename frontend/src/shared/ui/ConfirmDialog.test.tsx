// frontend/src/shared/ui/ConfirmDialog.test.tsx
import { StrictMode } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import ConfirmDialog from './ConfirmDialog'

describe('ConfirmDialog', () => {
    it('в StrictMode снимает блокировку после ошибки и разрешает повторить действие', async () => {
        const onConfirm = vi.fn().mockRejectedValueOnce(new Error('test')).mockResolvedValueOnce(undefined)
        render(
            <StrictMode>
                <ConfirmDialog title="Подтверждение" message="Продолжить?" onConfirm={onConfirm} onCancel={vi.fn()} />
            </StrictMode>,
        )
        fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
        expect(await screen.findByRole('alert')).toHaveTextContent('Операция не выполнена')
        const confirm = screen.getByRole('button', { name: 'Подтвердить' })
        expect(confirm).toBeEnabled()
        fireEvent.click(confirm)
        await waitFor(() => expect(confirm).toBeEnabled())
        expect(onConfirm).toHaveBeenCalledTimes(2)
    })
})

// frontend/src/features/erasure/ui/RequestIdCopy.test.tsx
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import RequestIdCopy from './RequestIdCopy'

const id = '4cc9a902-47a2-46f7-9594-49261e6dd90e'
const label = `Скопировать номер заявки ${id}`
function setClipboard(value: unknown) {
    const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value })
    return () => {
        if (original) Object.defineProperty(navigator, 'clipboard', original)
        else Reflect.deleteProperty(navigator, 'clipboard')
    }
}
let restore: () => void = () => undefined
afterEach(() => restore())
describe('Request ID copy', () => {
    it('copies the full visible UUID only on an explicit click and confirms success', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined)
        restore = setClipboard({ writeText })
        render(<RequestIdCopy requestId={id}/>)
        expect(writeText).not.toHaveBeenCalled()
        expect(screen.getByText(id)).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: label }))
        expect(await screen.findByText('Номер скопирован.')).toBeInTheDocument()
        expect(writeText).toHaveBeenCalledTimes(1)
        expect(writeText).toHaveBeenCalledWith(id)
    })
    it.each(['denied', 'unavailable'] as const)('keeps manual copy available when clipboard is %s', async kind => {
        restore = setClipboard(kind === 'denied' ? { writeText: vi.fn().mockRejectedValue(new Error('Denied')) } : undefined)
        render(<RequestIdCopy requestId={id}/>)
        fireEvent.click(screen.getByRole('button', { name: label }))
        expect(await screen.findByText(/Не удалось скопировать/)).toBeInTheDocument()
        expect(screen.getByText(id)).toBeInTheDocument()
        expect(screen.queryByText('Номер скопирован.')).not.toBeInTheDocument()
    })
})

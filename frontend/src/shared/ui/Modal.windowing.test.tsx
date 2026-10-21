// frontend/src/shared/ui/Modal.windowing.test.tsx
// Регрессионные тесты указателей, ограничений перемещения и максимизации.

import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import Modal from './Modal'

const dimensions = {
    initialWidth: 400,
    initialHeight: 300,
    minWidth: 240,
    minHeight: 180,
    maxWidth: 800,
    maxHeight: 600,
    maximizable: true,
} as const

function renderWindow() {
    render(
        <Modal title="Окно" onClose={vi.fn()} resize={dimensions}>
            <button type="button">Не перетаскивать</button>
        </Modal>,
    )
    return screen.getByRole('dialog')
}

describe('Управление окном указателем', () => {
    it('не начинает перетаскивание правой кнопкой, с кнопки или из тела окна', () => {
        const dialog = renderWindow()
        const handle = dialog.querySelector<HTMLElement>('[data-modal-drag-handle="true"]')!
        const initialLeft = dialog.style.left
        fireEvent.pointerDown(handle, { pointerId: 12, button: 2, clientX: 100, clientY: 100 })
        fireEvent.pointerMove(handle, { pointerId: 12, clientX: 300, clientY: 300 })
        expect(dialog.style.left).toBe(initialLeft)
        expect(document.body).not.toHaveClass('modal-dragging')
        fireEvent.pointerDown(screen.getByRole('button', { name: 'Не перетаскивать' }), {
            pointerId: 12, button: 0, clientX: 100, clientY: 100,
        })
        fireEvent.pointerMove(handle, { pointerId: 12, clientX: 300, clientY: 300 })
        expect(dialog.style.left).toBe(initialLeft)
    })

    it('игнорирует чужой pointerId при изменении размеров и очищает CSS-класс', () => {
        const dialog = renderWindow()
        const east = dialog.querySelector<HTMLElement>('[data-modal-resize-handle="e"]')!
        fireEvent.pointerDown(east, { pointerId: 1, button: 0, clientX: 700, clientY: 200 })
        expect(document.body).toHaveClass('modal-resizing')
        fireEvent.pointerMove(east, { pointerId: 2, clientX: 900, clientY: 200 })
        expect(dialog.style.width).toBe('400px')
        fireEvent.pointerMove(east, { pointerId: 1, clientX: 740, clientY: 200 })
        expect(dialog.style.width).toBe('440px')
        fireEvent.pointerCancel(east, { pointerId: 1 })
        expect(document.body).not.toHaveClass('modal-resizing')
    })

    it('очищает состояние drag при размонтировании', () => {
        const view = render(
            <Modal title="Перетаскивание" onClose={vi.fn()} resize={dimensions}>Содержимое</Modal>,
        )
        const handle = screen.getByRole('dialog')
            .querySelector<HTMLElement>('[data-modal-drag-handle="true"]')!
        fireEvent.pointerDown(handle, { pointerId: 3, button: 0, clientX: 100, clientY: 100 })
        expect(document.body).toHaveClass('modal-dragging')
        view.unmount()
        expect(document.body).not.toHaveClass('modal-dragging')
    })

    it('не позволяет менять размер развёрнутого окна и восстанавливает исходные границы', () => {
        const dialog = renderWindow()
        const originalWidth = dialog.style.width
        fireEvent.click(screen.getByRole('button', { name: 'Развернуть окно на весь экран' }))
        expect(dialog).toHaveClass('modal-card--maximized')
        expect(dialog.querySelectorAll('[data-modal-resize-handle]')).toHaveLength(0)
        fireEvent.click(screen.getByRole('button', { name: 'Восстановить размер окна' }))
        expect(dialog).not.toHaveClass('modal-card--maximized')
        expect(dialog.style.width).toBe(originalWidth)
        expect(dialog.querySelectorAll('[data-modal-resize-handle]')).toHaveLength(8)
    })

    it('подгоняет окно под viewport при изменении размера окна браузера', () => {
        const dialog = renderWindow()
        const width = window.innerWidth
        const height = window.innerHeight
        try {
            Object.defineProperty(window, 'innerWidth', { configurable: true, value: 360 })
            Object.defineProperty(window, 'innerHeight', { configurable: true, value: 300 })
            fireEvent(window, new Event('resize'))
            expect(Number.parseFloat(dialog.style.width)).toBeLessThanOrEqual(336)
            expect(Number.parseFloat(dialog.style.height)).toBeLessThanOrEqual(276)
            expect(Number.parseFloat(dialog.style.left)).toBeGreaterThanOrEqual(12)
            expect(Number.parseFloat(dialog.style.top)).toBeGreaterThanOrEqual(12)
        } finally {
            Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
            Object.defineProperty(window, 'innerHeight', { configurable: true, value: height })
        }
    })
})

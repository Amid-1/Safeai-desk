// frontend/src/shared/ui/Modal.lifecycle.test.tsx
// Регрессионные тесты политики закрытия, стека, фокуса и очистки.

import { createRef } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import Modal from './Modal'

describe('Жизненный цикл модального окна', () => {
    it('сохраняет публичную разметку и атрибуты доступности', () => {
        render(
            <Modal
                title="Пример"
                className="custom-modal"
                size="lg"
                descriptionId="modal-description"
                onClose={vi.fn()}
                footer={<button type="button">Применить</button>}
            >
                <p id="modal-description">Описание окна</p>
            </Modal>,
        )
        const dialog = screen.getByRole('dialog', { name: 'Пример' })
        expect(dialog).toHaveClass('modal-card', 'modal-card--lg', 'custom-modal')
        expect(dialog).toHaveAttribute('aria-modal', 'true')
        expect(dialog).toHaveAttribute('aria-describedby', 'modal-description')
        expect(dialog.querySelector('.modal-footer')).toContainElement(screen.getByText('Применить'))
        expect(document.getElementById('modal-root')).toContainElement(dialog)
    })

    it('блокирует фон, а затем восстанавливает прежние inert/aria-hidden/modal-open', () => {
        const root = document.createElement('div')
        root.id = 'root'
        root.setAttribute('inert', '')
        root.setAttribute('aria-hidden', 'false')
        document.body.append(root)
        document.body.classList.add('modal-open')
        const view = render(<Modal title="Защита" onClose={vi.fn()}>Текст</Modal>)
        expect(root).toHaveAttribute('inert')
        expect(root).toHaveAttribute('aria-hidden', 'true')
        view.unmount()
        expect(root).toHaveAttribute('inert')
        expect(root).toHaveAttribute('aria-hidden', 'false')
        expect(document.body).toHaveClass('modal-open')
        document.body.classList.remove('modal-open')
    })

    it('блокирует все способы закрытия при closeDisabled, но разрешает их после обновления props', () => {
        const onClose = vi.fn()
        const renderWindow = (disabled: boolean) => (
            <Modal title="Операция" onClose={onClose} closeDisabled={disabled}>
                <button type="button">Поле</button>
            </Modal>
        )
        const view = render(renderWindow(true))
        const dialog = screen.getByRole('dialog')
        expect(dialog).toHaveAttribute('aria-busy', 'true')
        expect(screen.getByRole('button', { name: /Закрытие недоступно/ })).toBeDisabled()
        fireEvent.pointerDown(dialog.parentElement!)
        fireEvent.keyDown(document, { key: 'Escape' })
        expect(onClose).not.toHaveBeenCalled()

        view.rerender(renderWindow(false))
        expect(dialog).toHaveAttribute('aria-busy', 'false')
        fireEvent.pointerDown(dialog.parentElement!)
        fireEvent.keyDown(document, { key: 'Escape' })
        fireEvent.click(screen.getByRole('button', { name: 'Закрыть окно' }))
        expect(onClose).toHaveBeenCalledTimes(3)
    })

    it('использует initialFocusRef и удерживает Tab внутри окна', () => {
        const focusRef = createRef<HTMLButtonElement>()
        render(
            <Modal title="Фокус" onClose={vi.fn()} initialFocusRef={focusRef}>
                <button type="button">Первый</button>
                <button type="button" ref={focusRef}>Второй</button>
            </Modal>,
        )
        const second = screen.getByRole('button', { name: 'Второй' })
        expect(second).toHaveFocus()
        const close = screen.getByRole('button', { name: 'Закрыть окно' })
        second.focus()
        fireEvent.keyDown(document, { key: 'Tab' })
        expect(close).toHaveFocus()
        close.focus()
        fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
        expect(second).toHaveFocus()
    })

    it('Escape и backdrop действуют только на верхнее окно; нижнее блокируется', () => {
        const closeOuter = vi.fn()
        const closeInner = vi.fn()
        const renderWindows = (inner: boolean) => (
            <>
                <Modal title="Внешнее" onClose={closeOuter}>
                    <button type="button">Открыть вложенное</button>
                </Modal>
                {inner && (
                    <Modal title="Вложенное" onClose={closeInner}>
                        <button type="button">Внутри</button>
                    </Modal>
                )}
            </>
        )
        const view = render(renderWindows(false))
        view.rerender(renderWindows(true))
        const outer = screen.getByRole('dialog', { name: 'Внешнее', hidden: true })
        const inner = screen.getByRole('dialog', { name: 'Вложенное' })
        expect(outer.parentElement).toHaveAttribute('inert')
        expect(outer.parentElement).toHaveAttribute('aria-hidden', 'true')
        fireEvent.pointerDown(outer.parentElement!)
        fireEvent.keyDown(document, { key: 'Escape' })
        expect(closeOuter).not.toHaveBeenCalled()
        expect(closeInner).toHaveBeenCalledTimes(1)
        fireEvent.pointerDown(inner.parentElement!)
        expect(closeInner).toHaveBeenCalledTimes(2)
        view.rerender(renderWindows(false))
        fireEvent.keyDown(document, { key: 'Escape' })
        expect(closeOuter).toHaveBeenCalledTimes(1)
    })

    it('не закрывает окно при событии на содержимом, всплывающем до backdrop', () => {
        const onClose = vi.fn()
        render(<Modal title="Внутренний клик" onClose={onClose}><button type="button">Внутри</button></Modal>)
        fireEvent.pointerDown(screen.getByRole('button', { name: 'Внутри' }))
        expect(onClose).not.toHaveBeenCalled()
    })

    it('при закрытии возвращает фокус инициатору', async () => {
        const trigger = document.createElement('button')
        trigger.textContent = 'Показать окно'
        document.body.append(trigger)
        trigger.focus()
        const view = render(<Modal title="Возврат" onClose={vi.fn()}><button type="button">Контроль</button></Modal>)
        expect(screen.getByRole('button', { name: 'Контроль' })).toHaveFocus()
        view.unmount()
        await waitFor(() => expect(trigger).toHaveFocus())
    })

    it('после демонтирования удаляет обработчик Escape', () => {
        const onClose = vi.fn()
        const view = render(<Modal title="Удаление" onClose={onClose}>Содержимое</Modal>)
        view.unmount()
        fireEvent.keyDown(document, { key: 'Escape' })
        expect(onClose).not.toHaveBeenCalled()
    })
})

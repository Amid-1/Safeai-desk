// frontend/src/shared/ui/ResizableScrollRegion.production.test.tsx
// ============================================================
// frontend/src/shared/ui/ResizableScrollRegion.production.test.tsx
// ============================================================
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import ResizableScrollRegion from './ResizableScrollRegion'
import type { ResizableScrollRegionProps } from './ResizableScrollRegion'

const KEY = 'safeai:resizable-production-test'

function boundingRect(height: number, top = 0): DOMRect {
    return {
        x: 0, y: top, width: 1200, height,
        left: 0, top, right: 1200, bottom: top + height,
        toJSON: () => ({}),
    } as DOMRect
}

function region(props: Partial<ResizableScrollRegionProps> = {}) {
    return (
        <ResizableScrollRegion
            storageKey={KEY}
            label="проверка"
            upper={<div>Верхний блок <span data-resizable-upper-boundary /></div>}
            footer={<span>Итог</span>}
            defaultHeight={350}
            minHeight={170}
            maxHeight={760}
            minUpperHeight={92}
            {...props}
        >
            <div>Таблица</div>
        </ResizableScrollRegion>
    )
}

function height(): number {
    return Number(screen.getByRole('separator').getAttribute('aria-valuenow'))
}

beforeEach(() => {
    window.localStorage.removeItem(KEY)
    window.localStorage.removeItem(`${KEY}:manual`)
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect')
        .mockImplementation(function (this: HTMLElement) {
            if (this.classList.contains('resizable-scroll-region')) return boundingRect(900)
            if (this.hasAttribute('data-resizable-upper-boundary')) return boundingRect(620)
            return boundingRect(500)
        })
})

afterEach(() => {
    window.localStorage.removeItem(KEY)
    window.localStorage.removeItem(`${KEY}:manual`)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    vi.restoreAllMocks()
})

describe('ResizableScrollRegion: полная регрессия поведения нижней панели', () => {
    it('сохраняет DOM-разделение, CSS-классы, footer и ARIA', () => {
        const view = render(region({
            className: 'custom-root',
            upperClassName: 'custom-upper',
            lowerClassName: 'custom-lower',
            viewportClassName: 'custom-viewport',
            footerClassName: 'custom-footer',
        }))
        expect(view.container.querySelector('.custom-root')).toBeInTheDocument()
        expect(view.container.querySelector('.custom-upper')).toHaveTextContent('Верхний блок')
        expect(view.container.querySelector('.custom-lower')).toBeInTheDocument()
        expect(view.container.querySelector('.custom-viewport')).toHaveTextContent('Таблица')
        expect(view.container.querySelector('.custom-footer')).toHaveTextContent('Итог')
        expect(screen.getByRole('separator')).toHaveAttribute('aria-orientation', 'horizontal')
        expect(screen.getByRole('separator')).toHaveAttribute('aria-valuenow', '350')
    })

    it('поддерживает клавиатуру, Home/End и сброс двойным щелчком', () => {
        render(region())
        const separator = screen.getByRole('separator')
        fireEvent.keyDown(separator, { key: 'ArrowUp' })
        expect(height()).toBe(382)
        fireEvent.keyDown(separator, { key: 'ArrowDown' })
        expect(height()).toBe(350)
        fireEvent.keyDown(separator, { key: 'Home' })
        expect(height()).toBe(170)
        fireEvent.keyDown(separator, { key: 'End' })
        expect(height()).toBe(760)
        fireEvent.doubleClick(separator)
        expect(height()).toBe(350)
    })

    it('использует сохранённую высоту и возвращает предпочтение после повторного создания', () => {
        window.localStorage.setItem(KEY, '470')
        const view = render(region())
        expect(height()).toBe(470)
        fireEvent.keyDown(screen.getByRole('separator'), { key: 'ArrowDown' })
        expect(height()).toBe(438)
        expect(window.localStorage.getItem(KEY)).toBe('438')
        view.unmount()
        render(region())
        expect(height()).toBe(438)
    })

    it('в режиме auto-fit не теряет ручную настройку и возвращается к auto-fit по двойному щелчку', () => {
        render(region({ autoFitHeight: 220 }))
        const separator = screen.getByRole('separator')
        expect(height()).toBe(220)
        fireEvent.keyDown(separator, { key: 'ArrowUp' })
        expect(height()).toBe(252)
        expect(window.localStorage.getItem(`${KEY}:manual`)).toBe('1')
        fireEvent.doubleClick(separator)
        expect(height()).toBe(220)
        expect(window.localStorage.getItem(`${KEY}:manual`)).toBeNull()
    })

    it('программные команды срабатывают только при изменении счётчика', async () => {
        const view = render(region({ showLowerRequest: 0, showUpperRequest: 0 }))
        expect(height()).toBe(350)
        view.rerender(region({ showLowerRequest: 1, showUpperRequest: 0 }))
        await waitFor(() => expect(height()).toBe(760))
        view.rerender(region({ showLowerRequest: 1, showUpperRequest: 1 }))
        await waitFor(() => expect(height()).toBe(170))
        view.rerender(region({ showLowerRequest: 1, showUpperRequest: 1 }))
        expect(height()).toBe(170)
    })

    it('защищает семантическую границу верхней области без наложения панели', async () => {
        render(region({ preserveUpperContent: true, upperBoundaryPadding: 8 }))
        await waitFor(() => expect(height()).toBe(272))
        fireEvent.keyDown(screen.getByRole('separator'), { key: 'End' })
        expect(height()).toBe(272)
        expect(screen.getByText('Таблица')).toBeInTheDocument()
    })

    it('при перетаскивании сохраняет прежний cursor/userSelect и блокирует чужой pointerId', () => {
        document.body.style.cursor = 'grab'
        document.body.style.userSelect = 'text'
        render(region())
        const separator = screen.getByRole('separator')
        fireEvent.pointerDown(separator, { pointerId: 11, button: 0, clientY: 200 })
        expect(document.body.style.cursor).toBe('row-resize')
        expect(document.body.style.userSelect).toBe('none')
        fireEvent.pointerMove(separator, { pointerId: 99, clientY: 100 })
        expect(height()).toBe(350)
        fireEvent.pointerMove(separator, { pointerId: 11, clientY: 150 })
        expect(height()).toBe(400)
        fireEvent.pointerUp(separator, { pointerId: 99 })
        expect(document.body.style.cursor).toBe('row-resize')
        fireEvent.pointerUp(separator, { pointerId: 11 })
        expect(document.body.style.cursor).toBe('grab')
        expect(document.body.style.userSelect).toBe('text')
    })

    it('не начинает перетаскивание правой кнопкой и очищается при размонтировании', () => {
        const view = render(region())
        const separator = screen.getByRole('separator')
        fireEvent.pointerDown(separator, { pointerId: 15, button: 2, clientY: 200 })
        expect(document.body.style.cursor).toBe('')
        fireEvent.pointerDown(separator, { pointerId: 15, button: 0, clientY: 200 })
        expect(document.body.style.cursor).toBe('row-resize')
        view.unmount()
        expect(document.body.style.cursor).toBe('')
        expect(document.body.style.userSelect).toBe('')
    })

    it('сброс прокрутки по ключу не зависит от команд изменения высоты', () => {
        const view = render(region({ viewportResetKey: 'first' }))
        const viewport = view.container.querySelector('.resizable-scroll-region__viewport') as HTMLElement
        viewport.scrollTop = 230
        fireEvent.keyDown(screen.getByRole('separator'), { key: 'ArrowUp' })
        expect(viewport.scrollTop).toBe(230)
        view.rerender(region({ viewportResetKey: 'second' }))
        expect(viewport.scrollTop).toBe(0)
    })

    it('при отмене pointer-события и потере захвата восстанавливает стили body', () => {
        render(region())
        const separator = screen.getByRole('separator')
        fireEvent.pointerDown(separator, { pointerId: 31, button: 0, clientY: 200 })
        expect(document.body.style.cursor).toBe('row-resize')
        fireEvent.pointerCancel(separator, { pointerId: 31 })
        expect(document.body.style.cursor).toBe('')
        fireEvent.pointerDown(separator, { pointerId: 32, button: 0, clientY: 200 })
        fireEvent.lostPointerCapture(separator, { pointerId: 32 })
        expect(document.body.style.cursor).toBe('')
        expect(document.body.style.userSelect).toBe('')
    })

    it('изменение высоты остаётся доступно, даже когда запись в localStorage запрещена', () => {
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new Error('Storage unavailable')
        })
        render(region())
        fireEvent.keyDown(screen.getByRole('separator'), { key: 'ArrowUp' })
        expect(height()).toBe(382)
    })

    it('корректно отображает нулевой размер нижней области при полном занятии viewport', () => {
        vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect')
            .mockImplementation(function (this: HTMLElement) {
                if (this.classList.contains('resizable-scroll-region')) return boundingRect(100)
                return boundingRect(200)
            })
        render(region({ minUpperHeight: 150 }))
        expect(height()).toBe(0)
        expect(screen.getByRole('separator')).toHaveAttribute('aria-valuemax', '0')
    })
})

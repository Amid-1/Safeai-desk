// frontend/src/shared/ui/resizableScrollRegion/regionStorage.test.ts
// ============================================================
// frontend/src/shared/ui/resizableScrollRegion/regionStorage.test.ts
// ============================================================
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
    beginBodyResizeStyle,
    clamp,
    readSavedHeight,
    restoreBodyResizeStyle,
} from './regionStorage'

const KEY = 'safeai:region-storage-unit-test'

afterEach(() => {
    window.localStorage.removeItem(KEY)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    vi.restoreAllMocks()
})

describe('ResizableScrollRegion: сохранённая высота и оформление перетаскивания', () => {
    it.each([
        [null, 350],
        ['280', 280],
        ['5', 170],
        ['1200', 760],
        ['bad', 350],
        ['Infinity', 350],
        ['NaN', 350],
    ])('нормализует localStorage %s в %s', (raw, expected) => {
        if (raw !== null) window.localStorage.setItem(KEY, raw)
        expect(readSavedHeight(KEY, 350, 170, 760)).toBe(expected)
    })

    it('при запрете чтения localStorage возвращает исходную ограниченную высоту', () => {
        vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
            throw new Error('Storage unavailable')
        })
        expect(readSavedHeight(KEY, 900, 170, 760)).toBe(760)
    })

    it('правильно ограничивает значения независимо от знака', () => {
        expect(clamp(-100, 10, 20)).toBe(10)
        expect(clamp(30, 10, 20)).toBe(20)
        expect(clamp(15, 10, 20)).toBe(15)
    })

    it('восстанавливает существующие inline-стили body', () => {
        document.body.style.cursor = 'crosshair'
        document.body.style.userSelect = 'text'
        const previous = beginBodyResizeStyle()
        expect(document.body.style.cursor).toBe('row-resize')
        expect(document.body.style.userSelect).toBe('none')
        restoreBodyResizeStyle(previous)
        expect(document.body.style.cursor).toBe('crosshair')
        expect(document.body.style.userSelect).toBe('text')
    })
})

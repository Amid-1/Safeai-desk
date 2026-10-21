// frontend/src/shared/ui/modal/modalGeometry.test.ts
// Регрессионные тесты геометрии окна: ограничения, восемь граней и масштаб.

import { describe, expect, it } from 'vitest'
import {
    calculateResizeScale,
    createInitialModalBounds,
    createMaximizedModalBounds,
    fitModalBoundsToViewport,
    getResizeLimits,
    resizeModalBounds,
} from './modalGeometry'
import type { ModalBounds, ModalResizeOptions, ResizeDirection } from './modalGeometry'

const options: ModalResizeOptions = {
    initialWidth: 400,
    initialHeight: 300,
    minWidth: 240,
    minHeight: 180,
    maxWidth: 800,
    maxHeight: 600,
    minScale: 0.5,
    maxScale: 2,
}

const initial: ModalBounds = { left: 300, top: 200, width: 400, height: 300 }

function resize(direction: ResizeDirection, dx: number, dy: number) {
    return resizeModalBounds({
        pointerId: 1,
        direction,
        startX: 0,
        startY: 0,
        startBounds: initial,
    }, dx, dy, options)
}

describe('Геометрия модального окна', () => {
    it.each([
        ['n', 0, -25, { left: 300, top: 175, width: 400, height: 325 }],
        ['ne', 30, -25, { left: 300, top: 175, width: 430, height: 325 }],
        ['e', 30, 0, { left: 300, top: 200, width: 430, height: 300 }],
        ['se', 30, 25, { left: 300, top: 200, width: 430, height: 325 }],
        ['s', 0, 25, { left: 300, top: 200, width: 400, height: 325 }],
        ['sw', -30, 25, { left: 270, top: 200, width: 430, height: 325 }],
        ['w', -30, 0, { left: 270, top: 200, width: 430, height: 300 }],
        ['nw', -30, -25, { left: 270, top: 175, width: 430, height: 325 }],
    ] as const)('корректно изменяет размер в направлении %s', (dir, dx, dy, expected) => {
        expect(resize(dir, dx, dy)).toEqual(expected)
    })

    it('не выходит за минимальные и максимальные размеры', () => {
        for (const direction of ['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw'] as const) {
            for (const delta of [-10_000, 10_000]) {
                const result = resize(direction, delta, delta)
                const limits = getResizeLimits(options)
                expect(result.width).toBeGreaterThanOrEqual(limits.minWidth)
                expect(result.width).toBeLessThanOrEqual(limits.maxWidth)
                expect(result.height).toBeGreaterThanOrEqual(limits.minHeight)
                expect(result.height).toBeLessThanOrEqual(limits.maxHeight)
            }
        }
    })

    it('помещает начальные размеры по центру и ограничивает окно видимой областью', () => {
        const bounds = createInitialModalBounds(options)
        expect(bounds.width).toBe(400)
        expect(bounds.height).toBe(300)
        expect(bounds.left).toBe((window.innerWidth - 400) / 2)
        expect(bounds.top).toBe((window.innerHeight - 300) / 2)

        const fitted = fitModalBoundsToViewport({
            left: -10_000,
            top: 10_000,
            width: 10_000,
            height: 10_000,
        }, options)
        const limits = getResizeLimits(options)
        expect(fitted.left).toBeGreaterThanOrEqual(12)
        expect(fitted.top).toBeGreaterThanOrEqual(12)
        expect(fitted.left + fitted.width).toBeLessThanOrEqual(limits.viewportWidth - 12)
        expect(fitted.top + fitted.height).toBeLessThanOrEqual(limits.viewportHeight - 12)
    })

    it('ограничивает минимальные размеры маленьким viewport', () => {
        const width = window.innerWidth
        const height = window.innerHeight
        try {
            Object.defineProperty(window, 'innerWidth', { configurable: true, value: 280 })
            Object.defineProperty(window, 'innerHeight', { configurable: true, value: 260 })
            const bounds = createInitialModalBounds(options)
            expect(bounds.width).toBeLessThanOrEqual(256)
            expect(bounds.height).toBeLessThanOrEqual(236)
        } finally {
            Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
            Object.defineProperty(window, 'innerHeight', { configurable: true, value: height })
        }
    })

    it('максимизация использует размер viewport', () => {
        expect(createMaximizedModalBounds()).toEqual({
            left: 0,
            top: 0,
            width: Math.max(320, window.innerWidth),
            height: Math.max(320, window.innerHeight),
        })
    })

    it('масштаб зависит от обеих осей и не выходит за minScale/maxScale', () => {
        const baseline = calculateResizeScale(initial, options)
        const horizontal = calculateResizeScale({ ...initial, width: 500 }, options)
        const both = calculateResizeScale({ ...initial, width: 500, height: 360 }, options)
        expect(horizontal).toBeGreaterThan(baseline)
        expect(both).toBeGreaterThan(horizontal)
        expect(calculateResizeScale({ ...initial, width: 5, height: 5 }, options)).toBe(0.5)
        expect(calculateResizeScale({ ...initial, width: 5_000, height: 5_000 }, options)).toBe(2)
    })
})

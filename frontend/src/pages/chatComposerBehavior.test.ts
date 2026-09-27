// frontend/src/pages/chatComposerBehavior.test.ts
import {
    describe,
    expect,
    it,
} from 'vitest'
import {
    shouldSubmitComposerKey,
} from './chatPage.helpers'

describe('chat composer keyboard behavior', () => {
    it('submits on plain Enter', () => {
        expect(
            shouldSubmitComposerKey({
                key: 'Enter',
                shiftKey: false,
                isComposing: false,
                repeat: false,
            }),
        ).toBe(true)
    })

    it('keeps Shift+Enter for a new line', () => {
        expect(
            shouldSubmitComposerKey({
                key: 'Enter',
                shiftKey: true,
                isComposing: false,
                repeat: false,
            }),
        ).toBe(false)
    })

    it('does not submit while an IME composition is active', () => {
        expect(
            shouldSubmitComposerKey({
                key: 'Enter',
                shiftKey: false,
                isComposing: true,
                repeat: false,
            }),
        ).toBe(false)
    })

    it('does not submit repeated keydown events from a held Enter key', () => {
        expect(
            shouldSubmitComposerKey({
                key: 'Enter',
                shiftKey: false,
                isComposing: false,
                repeat: true,
            }),
        ).toBe(false)
    })

    it('ignores non-Enter keys', () => {
        expect(
            shouldSubmitComposerKey({
                key: 'A',
                shiftKey: false,
                isComposing: false,
                repeat: false,
            }),
        ).toBe(false)
    })
})

// ============================================================
// frontend/src/components/Modal.test.tsx
// ============================================================

import {
    fireEvent,
    render,
    screen,
} from '@testing-library/react'
import {
    describe,
    expect,
    it,
    vi,
} from 'vitest'
import Modal from './Modal'

describe('Modal close policy', () => {
    it(
        'does not close on backdrop or Escape when explicitly disabled',
        () => {
            const onClose =
                vi.fn()

            render(
                <Modal
                    title="Persistent modal"
                    onClose={onClose}
                    closeOnBackdrop={false}
                    closeOnEscape={false}
                >
                    <button type="button">
                        Control
                    </button>
                </Modal>,
            )

            const dialog =
                screen.getByRole(
                    'dialog',
                )

            const backdrop =
                dialog.parentElement

            expect(
                backdrop,
            ).not.toBeNull()

            if (
                !backdrop
            ) {
                return
            }

            fireEvent.pointerDown(
                backdrop,
            )

            fireEvent.keyDown(
                document,
                {
                    key:
                        'Escape',
                },
            )

            expect(
                onClose,
            ).not.toHaveBeenCalled()

            fireEvent.click(
                screen.getByRole(
                    'button',
                    {
                        name:
                            'Закрыть окно',
                    },
                ),
            )

            expect(
                onClose,
            ).toHaveBeenCalledTimes(
                1,
            )
        },
    )

    it(
        'keeps legacy backdrop and Escape closing enabled by default',
        () => {
            const onClose =
                vi.fn()

            render(
                <Modal
                    title="Default modal"
                    onClose={onClose}
                >
                    <button type="button">
                        Control
                    </button>
                </Modal>,
            )

            const dialog =
                screen.getByRole(
                    'dialog',
                )

            const backdrop =
                dialog.parentElement

            expect(
                backdrop,
            ).not.toBeNull()

            if (
                !backdrop
            ) {
                return
            }

            fireEvent.pointerDown(
                backdrop,
            )

            expect(
                onClose,
            ).toHaveBeenCalledTimes(
                1,
            )

            fireEvent.keyDown(
                document,
                {
                    key:
                        'Escape',
                },
            )

            expect(
                onClose,
            ).toHaveBeenCalledTimes(
                2,
            )
        },
    )

    it(
        'resizes from an edge and scales content on both axes',
        () => {
            render(
                <Modal
                    title="Resizable modal"
                    onClose={vi.fn()}
                    resize={{
                        initialWidth: 400,
                        initialHeight: 300,
                        minWidth: 240,
                        minHeight: 180,
                        maxWidth: 800,
                        maxHeight: 600,
                        scaleContent: true,
                        minScale: 0.5,
                        maxScale: 2,
                    }}
                >
                    <button type="button">
                        Control
                    </button>
                </Modal>,
            )

            const dialog =
                screen.getByRole(
                    'dialog',
                )

            const eastHandle =
                dialog.querySelector<HTMLElement>(
                    '[data-modal-resize-handle="e"]',
                )

            const southHandle =
                dialog.querySelector<HTMLElement>(
                    '[data-modal-resize-handle="s"]',
                )

            expect(eastHandle)
                .not.toBeNull()
            expect(southHandle)
                .not.toBeNull()

            if (
                !eastHandle
                || !southHandle
            ) {
                return
            }

            fireEvent.pointerDown(
                eastHandle,
                {
                    pointerId: 1,
                    button: 0,
                    clientX: 712,
                    clientY: 300,
                },
            )
            fireEvent.pointerMove(
                eastHandle,
                {
                    pointerId: 1,
                    clientX: 812,
                    clientY: 300,
                },
            )
            fireEvent.pointerUp(
                eastHandle,
                {
                    pointerId: 1,
                },
            )

            expect(dialog.style.width)
                .toBe('500px')

            const horizontalScale =
                Number.parseFloat(
                    dialog.style.getPropertyValue(
                        '--modal-resize-scale',
                    ),
                )

            expect(horizontalScale)
                .toBeGreaterThan(1)

            fireEvent.pointerDown(
                southHandle,
                {
                    pointerId: 2,
                    button: 0,
                    clientX: 500,
                    clientY: 534,
                },
            )
            fireEvent.pointerMove(
                southHandle,
                {
                    pointerId: 2,
                    clientX: 500,
                    clientY: 594,
                },
            )
            fireEvent.pointerUp(
                southHandle,
                {
                    pointerId: 2,
                },
            )

            expect(dialog.style.height)
                .toBe('360px')

            const twoAxisScale =
                Number.parseFloat(
                    dialog.style.getPropertyValue(
                        '--modal-resize-scale',
                    ),
                )

            expect(twoAxisScale)
                .toBeGreaterThan(
                    horizontalScale,
                )
        },
    )

    it(
        'drags a resizable modal by its header and keeps it in the viewport',
        () => {
            render(
                <Modal
                    title="Draggable modal"
                    onClose={vi.fn()}
                    resize={{
                        initialWidth: 400,
                        initialHeight: 300,
                        minWidth: 240,
                        minHeight: 180,
                        maxWidth: 800,
                        maxHeight: 600,
                    }}
                >
                    Content
                </Modal>,
            )

            const dialog =
                screen.getByRole(
                    'dialog',
                )

            const header =
                dialog.querySelector<HTMLElement>(
                    '[data-modal-drag-handle="true"]',
                )

            expect(header)
                .not.toBeNull()

            if (!header) {
                return
            }

            fireEvent.pointerDown(
                header,
                {
                    pointerId: 3,
                    button: 0,
                    clientX: 400,
                    clientY: 250,
                },
            )
            fireEvent.pointerMove(
                header,
                {
                    pointerId: 3,
                    clientX: 500,
                    clientY: 300,
                },
            )

            expect(dialog.style.left)
                .toBe('412px')
            expect(dialog.style.top)
                .toBe('284px')

            fireEvent.pointerMove(
                header,
                {
                    pointerId: 3,
                    clientX: -2_000,
                    clientY: -2_000,
                },
            )
            fireEvent.pointerUp(
                header,
                {
                    pointerId: 3,
                },
            )

            expect(dialog.style.left)
                .toBe('12px')
            expect(dialog.style.top)
                .toBe('12px')
            expect(document.body)
                .not.toHaveClass(
                    'modal-dragging',
                )
        },
    )

    it(
        'maximizes and restores the previous resizable geometry',
        () => {
            render(
                <Modal
                    title="Window modal"
                    onClose={vi.fn()}
                    resize={{
                        initialWidth: 400,
                        initialHeight: 300,
                        minWidth: 240,
                        minHeight: 180,
                        maxWidth: 800,
                        maxHeight: 600,
                        maximizable: true,
                    }}
                >
                    Content
                </Modal>,
            )

            const dialog =
                screen.getByRole('dialog')

            const maximizeButton =
                screen.getByRole(
                    'button',
                    {
                        name: 'Развернуть окно на весь экран',
                    },
                )

            fireEvent.click(maximizeButton)

            expect(dialog)
                .toHaveClass('modal-card--maximized')
            expect(dialog.style.left)
                .toBe('0px')
            expect(dialog.style.top)
                .toBe('0px')
            expect(dialog.style.width)
                .toBe(`${window.innerWidth}px`)
            expect(dialog.style.height)
                .toBe(`${window.innerHeight}px`)
            expect(
                dialog.querySelector(
                    '[data-modal-resize-handle]',
                ),
            ).toBeNull()

            fireEvent.click(
                screen.getByRole(
                    'button',
                    {
                        name: 'Восстановить размер окна',
                    },
                ),
            )

            expect(dialog)
                .not.toHaveClass('modal-card--maximized')
            expect(dialog.style.width)
                .toBe('400px')
            expect(dialog.style.height)
                .toBe('300px')

            const header =
                dialog.querySelector<HTMLElement>(
                    '[data-modal-drag-handle="true"]',
                )

            expect(header).not.toBeNull()

            if (!header) {
                return
            }

            fireEvent.doubleClick(header)

            expect(dialog)
                .toHaveClass('modal-card--maximized')

            fireEvent.doubleClick(header)

            expect(dialog)
                .not.toHaveClass('modal-card--maximized')
        },
    )
})

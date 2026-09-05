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
})

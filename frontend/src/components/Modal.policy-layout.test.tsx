// ============================================================
// frontend/src/components/Modal.policy-layout.test.tsx
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

describe('Modal policy layout support', () => {
    it(
        'renders footer outside modal body',
        () => {
            render(
                <Modal
                    title="Test"
                    footer={
                        <button type="button">
                            Save
                        </button>
                    }
                    onClose={vi.fn()}
                >
                    <div>
                        Body
                    </div>
                </Modal>,
            )

            const dialog =
                screen.getByRole(
                    'dialog',
                )

            const body =
                dialog.querySelector(
                    '.modal-body',
                )

            const footer =
                dialog.querySelector(
                    '.modal-footer',
                )

            expect(body)
                .not.toBeNull()

            expect(footer)
                .not.toBeNull()

            expect(
                body?.contains(
                    footer,
                ),
            ).toBe(false)
        },
    )

    it(
        'keeps persistent modal open on backdrop and Escape',
        () => {
            const onClose =
                vi.fn()

            render(
                <Modal
                    title="Persistent"
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

            expect(backdrop)
                .not.toBeNull()

            if (!backdrop) {
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

            expect(onClose)
                .not.toHaveBeenCalled()

            fireEvent.click(
                screen.getByRole(
                    'button',
                    {
                        name:
                            'Закрыть окно',
                    },
                ),
            )

            expect(onClose)
                .toHaveBeenCalledTimes(
                    1,
                )
        },
    )
})

// frontend/src/features/models/ui/ModelCatalogVersionModal.test.tsx
/* ============================================================
   frontend/src/features/models/ui/ModelCatalogVersionModal.test.tsx
   ============================================================ */

import {fireEvent, render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {ModelCatalogVersionModal} from './ModelCatalogVersionModal'
import {
    createModelCatalogEntry,
    createRuntimeModelStatus,
} from '../../../test/fixtures/modelControlPlaneTestFixtures'

const runtime = createRuntimeModelStatus()
const baseEntry = createModelCatalogEntry()

describe('ModelCatalogVersionModal version semantics', () => {
    it('moves the add-model dialog by its heading without losing the draft', () => {
        vi.stubGlobal('innerWidth', 1920)
        vi.stubGlobal('innerHeight', 1200)
        try {
            render(
                <ModelCatalogVersionModal
                    base={null}
                    runtime={runtime}
                    catalogByKey={new Map()}
                    pending={false}
                    onClose={vi.fn()}
                    onSubmit={vi.fn()}
                />,
            )
            const key = screen.getByRole('textbox', {name: /Ключ модели/})
            fireEvent.change(key, {target: {value: 'vendor:new-model'}})
            const dialog = screen.getByRole('dialog', {name: 'Добавление модели в каталог'})
            const heading = screen.getByRole('heading', {name: 'Добавление модели в каталог'})
            expect(dialog.querySelector('[data-modal-drag-handle="true"]')).toBeInTheDocument()
            const left = Number.parseFloat(dialog.style.left)
            const top = Number.parseFloat(dialog.style.top)
            fireEvent.pointerDown(heading, {pointerId: 1, button: 0, clientX: 800, clientY: 180})
            fireEvent.pointerMove(heading, {pointerId: 1, clientX: 880, clientY: 220})
            fireEvent.pointerUp(heading, {pointerId: 1})
            expect(dialog.style.left).toBe(`${left + 80}px`)
            expect(dialog.style.top).toBe(`${top + 40}px`)
            expect(key).toHaveValue('vendor:new-model')
            expect(document.body).not.toHaveClass('modal-dragging')
        } finally {
            vi.unstubAllGlobals()
        }
    })

    it('explains an existing key as a new version even from Create model', () => {
        render(
            <ModelCatalogVersionModal
                base={null}
                runtime={runtime}
                catalogByKey={new Map([
                    [
                        baseEntry.modelKey,
                        createModelCatalogEntry({version: 2}),
                    ],
                ])}
                pending={false}
                onClose={vi.fn()}
                onSubmit={vi.fn().mockResolvedValue(undefined)}
            />,
        )

        expect(
            screen.getByText(/После сохранения появится версия 3/),
        ).toBeInTheDocument()
        expect(
            screen.getByRole('heading', {
                name: 'Новая версия: mock-safeai',
            }),
        ).toBeInTheDocument()

        fireEvent.change(
            screen.getByRole('textbox', {name: /Ключ модели/}),
            {
                target: {value: 'mock:new-model'},
            },
        )

        expect(
            screen.getByText(/После сохранения появится первая версия модели/),
        ).toBeInTheDocument()
        expect(
            screen.getByRole('heading', {
                name: 'Добавление модели в каталог',
            }),
        ).toBeInTheDocument()
    })
})

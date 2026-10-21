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

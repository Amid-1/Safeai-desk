// frontend/src/features/models/ui/catalog/ModelsCatalogStructure.test.tsx
// Responsive card labels reuse the actual desktop table; the same operations are available on both.
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { createModelCatalogEntry, createRuntimeModelStatus } from '../../../../test/fixtures/modelControlPlaneTestFixtures'
import { CatalogTable } from '../modelControlPlaneViews/CatalogTable'

describe('каталог: структура, мобильная раскладка и действия', () => {
    it('сохраняет все девять полей и действия в едином семантическом DOM', () => {
        const entry = createModelCatalogEntry()
        const onCreateVersion = vi.fn()
        const onOpenHistory = vi.fn()
        const { container } = render(<CatalogTable
            entries={[entry]}
            effectiveEntries={[entry]}
            runtime={createRuntimeModelStatus()}
            canEdit
            onCreateVersion={onCreateVersion}
            onOpenHistory={onOpenHistory}
        />)
        const headers = ['Модель','Подключение','Статус каталога','Лимиты',
            'Возможности каталога','Стоимость','Политика данных','Действует с','Действия']
        expect(screen.getAllByRole('columnheader')).toHaveLength(headers.length)
        expect(Array.from(container.querySelectorAll('tbody td')).map((cell) => cell.getAttribute('data-label')))
            .toEqual(headers)
        fireEvent.click(screen.getByRole('button', {name: 'История версий'}))
        fireEvent.click(screen.getByRole('button', {name: 'Новая версия'}))
        expect(onOpenHistory).toHaveBeenCalledWith(entry)
        expect(onCreateVersion).toHaveBeenCalledWith(entry)
    })

    it('оставляет обычному ADMIN просмотр истории, но не разрешает изменение каталога', () => {
        const entry = createModelCatalogEntry()
        const onCreateVersion = vi.fn()
        render(<CatalogTable entries={[entry]} effectiveEntries={[entry]}
            runtime={createRuntimeModelStatus()} canEdit={false}
            onCreateVersion={onCreateVersion} onOpenHistory={vi.fn()} />)
        expect(screen.getByRole('button', {name:'История версий'})).toBeInTheDocument()
        expect(screen.queryByRole('button', {name:'Новая версия'})).not.toBeInTheDocument()
        expect(onCreateVersion).not.toHaveBeenCalled()
    })
})

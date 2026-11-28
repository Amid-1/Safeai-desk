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
            'Возможности модели','Стоимость','Конфиденциальность данных','Действует с','Действия']
        expect(screen.getAllByRole('columnheader')).toHaveLength(headers.length)
        expect(screen.getAllByRole('columnheader').every((header) => header.getAttribute('scope') === 'col')).toBe(true)
        expect(Array.from(container.querySelectorAll('tbody td')).map((cell) => cell.getAttribute('data-label')))
            .toEqual(headers)
        fireEvent.click(screen.getByRole('button', {name: 'История версий'}))
        fireEvent.click(screen.getByRole('button', {name: 'Новая версия'}))
        expect(onOpenHistory).toHaveBeenCalledWith(entry)
        expect(onCreateVersion).toHaveBeenCalledWith(entry)
    })

    it('отделяет сведения каталога от действительных гарантий точного подключения', () => {
        const connected = createModelCatalogEntry({provider: 'openai', providerModelId: 'model-a'})
        const foreign = createModelCatalogEntry({id: 'other', modelKey: 'other', provider: 'openai', providerModelId: 'model-b'})
        const runtime = createRuntimeModelStatus({provider: 'openai', model: 'model-a', enabled: true,
            dataAssurance: {noTraining: true, zeroDataRetention: false, source: 'CONTRACT-42',
                verifiedAt: '2000-01-01T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z'}})
        const { rerender } = render(<CatalogTable entries={[connected, foreign]} effectiveEntries={[connected, foreign]}
            runtime={runtime} canEdit={false} onCreateVersion={vi.fn()} onOpenHistory={vi.fn()} />)
        expect(screen.getAllByText('Хранение: Не указано')).toHaveLength(2)
        expect(screen.getByText('Запрет обучения подтверждён')).toHaveClass('models-status--success')
        expect(screen.getByText('Нулевое хранение не подтверждено')).toHaveClass('models-status--warning')
        expect(screen.getByText('Нет совпадающего подключения')).toBeInTheDocument()
        rerender(<CatalogTable entries={[connected, foreign]} effectiveEntries={[connected, foreign]}
            runtime={{...runtime, dataAssurance: {...runtime.dataAssurance!, expiresAt: '2001-01-01T00:00:00Z'}}}
            canEdit={false} onCreateVersion={vi.fn()} onOpenHistory={vi.fn()} />)
        expect(screen.queryByText('Запрет обучения подтверждён')).not.toBeInTheDocument()
        expect(screen.getByText('Запрет обучения не подтверждён')).toBeInTheDocument()
    })

    it('не превращает заявленные ограничения в подтверждение провайдера', () => {
        const entry = createModelCatalogEntry({retentionStatus: 'ZERO_DATA_RETENTION', trainingUseStatus: 'CONTRACTUAL_NO_TRAINING'})
        render(<CatalogTable entries={[entry]} effectiveEntries={[entry]}
            runtime={createRuntimeModelStatus({dataAssurance: undefined})} canEdit={false}
            onCreateVersion={vi.fn()} onOpenHistory={vi.fn()} />)
        expect(screen.getByText('Нулевое хранение не подтверждено')).toBeInTheDocument()
        expect(screen.getByText('Запрет обучения не подтверждён')).toBeInTheDocument()
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

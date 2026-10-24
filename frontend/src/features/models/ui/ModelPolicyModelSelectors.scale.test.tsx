// frontend/src/features/models/ui/ModelPolicyModelSelectors.scale.test.tsx
// Регрессии каталогов на десятки/сотни моделей: поиск, прокрутка и безопасность правил.

import {useState} from 'react'
import {fireEvent, render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {createModelCatalogEntry, createRuntimeModelStatus} from '../../../test/fixtures/modelControlPlaneTestFixtures'
import {DefaultModelSelector, ModelKeySelector} from './ModelPolicyModelSelectors'

const runtime = createRuntimeModelStatus()
const catalog = Array.from({length: 210}, (_, i) => createModelCatalogEntry({
    id: `00000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`,
    modelKey: `mock:choice-${String(i + 1).padStart(3, '0')}`,
    providerModelId: `choice-${String(i + 1).padStart(3, '0')}`,
    displayName: `Модель ${String(i + 1).padStart(3, '0')}`,
}))

function KeyHarness({initial = '', conflicting = '', kind = 'allow'}: {
    initial?: string; conflicting?: string; kind?: 'allow' | 'deny'
}) {
    const [value, setValue] = useState(initial)
    return <ModelKeySelector
        label={kind === 'allow' ? 'Разрешённые модели' : 'Запрещённые модели'}
        hint="Дополнительная информация"
        kind={kind}
        catalog={catalog}
        effectiveCatalog={catalog}
        runtime={runtime}
        value={value}
        conflictingValue={conflicting}
        onChange={setValue}
    />
}

describe('Масштабируемые списки правил моделей', () => {
    it('сохраняет 200 выбранных ключей без обрезания и даёт искать внутри них', () => {
        const initial = catalog.slice(0, 200).map(entry => entry.modelKey).join('\n')
        const {container} = render(<KeyHarness initial={initial} />)
        const list = screen.getByRole('list', {name: 'Разрешённые модели: выбранные модели'})
        expect(list).toHaveClass('models-model-selector__chips--dense')
        expect(container.querySelectorAll('.models-model-chip')).toHaveLength(200)
        const search = screen.getByRole('searchbox', {name: /Поиск среди выбранных/})
        fireEvent.change(search, {target: {value: 'choice-150'}})
        expect(container.querySelectorAll('.models-model-chip')).toHaveLength(1)
        expect(screen.getByText(/Найдено среди выбранных: 1 из 200/)).toBeInTheDocument()
        fireEvent.change(search, {target: {value: ''}})
        expect(container.querySelectorAll('.models-model-chip')).toHaveLength(200)
    })

    it('масштабирует запрещённый список, не допуская конфликтов и не трогая серверный контракт', () => {
        const initial = catalog.slice(0, 15).map(entry => entry.modelKey).join('\n')
        render(<KeyHarness kind="deny" initial={initial} />)
        const list = screen.getByRole('list', {name: 'Запрещённые модели: выбранные модели'})
        expect(list).toHaveClass('models-model-selector__chips--dense')
        expect(list.querySelectorAll('.models-model-chip')).toHaveLength(15)
        const search = screen.getByRole('searchbox', {name: /Поиск среди выбранных/})
        fireEvent.change(search, {target: {value: 'choice-011'}})
        expect(list.querySelectorAll('.models-model-chip')).toHaveLength(1)
        fireEvent.change(search, {target: {value: ''}})
        expect(list.querySelectorAll('.models-model-chip')).toHaveLength(15)
    })

    it('даёт выбрать десятую и последующие модели из каталога и показывает границу выдачи', () => {
        render(<KeyHarness />)
        const search = screen.getByRole('combobox', {name: 'Разрешённые модели'})
        fireEvent.focus(search)
        expect(screen.getByText(/Показаны первые 200 из 210/)).toBeInTheDocument()
        expect(screen.getAllByRole('option')).toHaveLength(200)
        fireEvent.change(search, {target: {value: 'choice-210'}})
        expect(screen.getByRole('option', {name: /choice-210/})).toBeInTheDocument()
        fireEvent.click(screen.getByRole('option', {name: /choice-210/}))
        expect(screen.getByRole('button', {name: 'Удалить mock:choice-210'})).toBeInTheDocument()
    })

    it('не позволяет перенести ключ из списка разрешённых в запрещённые', () => {
        render(<KeyHarness conflicting="mock:choice-100" />)
        const search = screen.getByRole('combobox', {name: 'Разрешённые модели'})
        fireEvent.change(search, {target: {value: 'mock:choice-100'}})
        const conflict = screen.getByRole('option', {name: /choice-100/})
        expect(conflict).toBeDisabled()
        fireEvent.keyDown(search, {key: 'Enter'})
        expect(screen.getByRole('alert')).toHaveTextContent('списке запрещённых')
    })

    it('показывает более восьми вариантов модели по умолчанию и ищет девятую', () => {
        const onChange = vi.fn()
        render(<DefaultModelSelector
            catalog={catalog}
            effectiveCatalog={catalog}
            runtime={runtime}
            allowModelKeys=""
            denyModelKeys=""
            value=""
            onChange={onChange}
        />)
        fireEvent.click(screen.getByRole('button', {name: /Использовать подключённую модель/}))
        expect(screen.getAllByRole('option')).toHaveLength(201) // 200 + автоматический режим
        expect(screen.getByText(/Показаны первые 200 из 210/)).toBeInTheDocument()
        const search = screen.getByRole('combobox', {name: 'Найти модель по умолчанию'})
        fireEvent.change(search, {target: {value: 'choice-210'}})
        fireEvent.keyDown(search, {key: 'Enter'})
        expect(onChange).toHaveBeenCalledWith('mock:choice-210')
    })
})

// frontend/src/components/admin/models/ModelPolicyModelSelectors.safety.test.tsx
// Регрессии валидации ключей, массовой вставки, лимитов и доступности селекторов.

import {useState} from 'react'
import {fireEvent, render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {createModelCatalogEntry, createRuntimeModelStatus} from './ModelControlPlaneTestFixtures'
import {DefaultModelSelector, ModelKeySelector} from './ModelPolicyModelSelectors'
import type {ModelKeySelectorInteractionState} from './ModelPolicyModelSelectors'

const runtime = createRuntimeModelStatus()
const catalog = [
    createModelCatalogEntry(),
    createModelCatalogEntry({id: '00000000-0000-0000-0000-000000000102', modelKey: 'mock:second', providerModelId: 'second', displayName: 'Вторая модель'}),
]

function KeyHarness({
    initial = '', conflicting = '', disabled = false,
    interaction = vi.fn<(state: ModelKeySelectorInteractionState) => void>(),
    changed = vi.fn<(value: string) => void>(),
}: {
    initial?: string,
    conflicting?: string,
    disabled?: boolean,
    interaction?: (state: ModelKeySelectorInteractionState) => void,
    changed?: (value: string) => void,
}) {
    const [value, setValue] = useState(initial)
    return <ModelKeySelector
        label="Разрешённые модели" hint="Пояснение" kind="allow"
        catalog={catalog} effectiveCatalog={catalog} runtime={runtime}
        value={value} conflictingValue={conflicting} disabled={disabled}
        onChange={next => {setValue(next); changed(next)}}
        onInteractionStateChange={interaction}
    />
}

function input() {return screen.getByRole('combobox', {name: 'Разрешённые модели'})}
function chips() {return document.querySelectorAll('.models-model-chip')}

describe('ModelKeySelector: обязательные ограничения', () => {
    it('не оставляет частично вставленные ключи при единственном неверном значении', () => {
        const changed = vi.fn()
        render(<KeyHarness changed={changed} />)
        fireEvent.paste(input(), {clipboardData: {getData: () => 'mock:valid\nmodel with spaces'}})
        expect(changed).not.toHaveBeenCalled()
        expect(chips()).toHaveLength(0)
        expect(screen.getByRole('alert')).toHaveTextContent('Не удалось добавить')
    })

    it('не оставляет частичный результат массовой вставки при конфликте списков', () => {
        const changed = vi.fn()
        render(<KeyHarness conflicting="mock:forbidden" changed={changed} />)
        fireEvent.paste(input(), {clipboardData: {getData: () => 'mock:valid;MOCK:FORBIDDEN'}})
        expect(changed).not.toHaveBeenCalled()
        expect(chips()).toHaveLength(0)
        expect(screen.getByRole('alert')).toHaveTextContent('списке запрещённых')
    })

    it('сохраняет ровно 200 моделей и запрещает 201-ю', () => {
        const keys = Array.from({length: 200}, (_, i) => `mock:k${i}`).join('\n')
        const changed = vi.fn()
        render(<KeyHarness initial={keys} changed={changed} />)
        expect(chips()).toHaveLength(200)
        fireEvent.change(input(), {target: {value: 'mock:additional'}})
        fireEvent.keyDown(input(), {key: 'Enter'})
        expect(changed).not.toHaveBeenCalled()
        expect(screen.getByRole('alert')).toHaveTextContent('не более 200 моделей')
        expect(chips()).toHaveLength(200)
    })

    it('проверяет лимит атомарно и при вставке 201 ключа', () => {
        const changed = vi.fn()
        const keys = Array.from({length: 201}, (_, i) => `mock:k${i}`).join(',')
        render(<KeyHarness changed={changed} />)
        fireEvent.paste(input(), {clipboardData: {getData: () => keys}})
        expect(changed).not.toHaveBeenCalled()
        expect(chips()).toHaveLength(0)
        expect(screen.getByRole('alert')).toHaveTextContent('не более 200 моделей')
    })

    it('не модифицирует список, когда редактор недоступен', () => {
        const changed = vi.fn()
        render(<KeyHarness initial="mock:mock-safeai" disabled changed={changed} />)
        expect(input()).toBeDisabled()
        expect(screen.getByRole('button', {name: 'Удалить mock:mock-safeai'})).toBeDisabled()
        fireEvent.click(screen.getByRole('button', {name: 'Удалить mock:mock-safeai'}))
        expect(chips()).toHaveLength(1)
        expect(changed).not.toHaveBeenCalled()
    })

    it('удаляет выбранную модель и сообщает чистое состояние взаимодействия', () => {
        const changed = vi.fn()
        const interaction = vi.fn()
        render(<KeyHarness initial="mock:mock-safeai" changed={changed} interaction={interaction} />)
        fireEvent.click(screen.getByRole('button', {name: 'Удалить mock:mock-safeai'}))
        expect(chips()).toHaveLength(0)
        expect(changed).toHaveBeenCalledWith('')
        expect(interaction).toHaveBeenCalledWith({hasPendingInput: false, hasError: false})
    })

    it('отмечает незавершённый поиск, который нельзя отправлять как allowlist', () => {
        const interaction = vi.fn()
        render(<KeyHarness interaction={interaction} />)
        fireEvent.change(input(), {target: {value: 'mock'}})
        expect(interaction).toHaveBeenLastCalledWith({hasPendingInput: true, hasError: false})
        fireEvent.change(input(), {target: {value: ''}})
        expect(interaction).toHaveBeenLastCalledWith({hasPendingInput: false, hasError: false})
    })

    it('поддерживает разделитель точку с запятой и нормализует регистр', () => {
        const changed = vi.fn()
        render(<KeyHarness changed={changed} />)
        fireEvent.change(input(), {target: {value: 'MOCK:NEW'}})
        fireEvent.keyDown(input(), {key: ';'})
        expect(changed).toHaveBeenCalledWith('mock:new')
    })

    it('сворачивает список без изменения данных по Escape и внешнему pointerdown', () => {
        const changed = vi.fn()
        render(<KeyHarness changed={changed} />)
        fireEvent.focus(input())
        expect(input()).toHaveAttribute('aria-expanded', 'true')
        fireEvent.keyDown(input(), {key: 'Escape'})
        expect(input()).toHaveAttribute('aria-expanded', 'false')
        fireEvent.focus(input())
        fireEvent.pointerDown(document.body)
        expect(input()).toHaveAttribute('aria-expanded', 'false')
        expect(changed).not.toHaveBeenCalled()
    })
})

describe('DefaultModelSelector: допустимый выбор', () => {
    const props = {
        catalog, effectiveCatalog: catalog, runtime,
        allowModelKeys: 'mock:mock-safeai\nmock:future',
        denyModelKeys: 'mock:second',
    }

    it('оставляет автоматический режим доступным, не обещая смену Runtime', () => {
        const onChange = vi.fn()
        render(<DefaultModelSelector {...props} value="mock:mock-safeai" onChange={onChange} />)
        fireEvent.click(screen.getByRole('button', {name: /Закреплена/}))
        fireEvent.click(screen.getByRole('option', {name: /Использовать подключённую модель/}))
        expect(onChange).toHaveBeenCalledWith('')
    })

    it('не предлагает запрещённую модель и показывает будущий ключ из allowlist', () => {
        render(<DefaultModelSelector {...props} value="" onChange={vi.fn()} />)
        fireEvent.click(screen.getByRole('button', {name: /Использовать подключённую модель/}))
        expect(screen.queryByRole('option', {name: /mock:second/})).not.toBeInTheDocument()
        expect(screen.getByRole('option', {name: /mock:future/})).toBeInTheDocument()
    })

    it('возвращает фокус на триггер при Escape и не меняет модель', () => {
        const onChange = vi.fn()
        render(<DefaultModelSelector {...props} value="" onChange={onChange} />)
        const trigger = screen.getByRole('button', {name: /Использовать подключённую модель/})
        fireEvent.click(trigger)
        const search = screen.getByRole('combobox', {name: 'Найти модель по умолчанию'})
        expect(search).toHaveFocus()
        fireEvent.keyDown(search, {key: 'Escape'})
        expect(trigger).toHaveFocus()
        expect(onChange).not.toHaveBeenCalled()
    })

    it('поддерживает фильтрацию и выбор клавиатурой без обхода allowlist', () => {
        const onChange = vi.fn()
        render(<DefaultModelSelector {...props} value="" onChange={onChange} />)
        fireEvent.click(screen.getByRole('button', {name: /Использовать подключённую модель/}))
        const search = screen.getByRole('combobox', {name: 'Найти модель по умолчанию'})
        fireEvent.change(search, {target: {value: 'future'}})
        fireEvent.keyDown(search, {key: 'ArrowDown'})
        fireEvent.keyDown(search, {key: 'Enter'})
        expect(onChange).toHaveBeenCalledWith('mock:future')
    })

    it('блокирует пользовательское взаимодействие с селектором в режиме disabled', () => {
        const onChange = vi.fn()
        render(<DefaultModelSelector {...props} value="" onChange={onChange} disabled />)
        const trigger = screen.getByRole('button', {name: /Использовать подключённую модель/})
        expect(trigger).toBeDisabled()
        fireEvent.click(trigger)
        expect(screen.queryByRole('combobox', {name: 'Найти модель по умолчанию'})).not.toBeInTheDocument()
        expect(onChange).not.toHaveBeenCalled()
    })
})

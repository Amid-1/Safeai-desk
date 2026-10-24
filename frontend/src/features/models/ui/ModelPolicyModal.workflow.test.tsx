// frontend/src/features/models/ui/ModelPolicyModal.workflow.test.tsx
// Регрессии: явный перевод ключа, безопасность пустого allowlist, preview и устаревший контекст.

import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {ModelPolicyModal} from './ModelPolicyModal'
import {movePolicyModelKey} from './modelPolicySelectors/selectorSupport'
import {
    createModelCatalogEntry,
    createOrganizationModelPolicy,
    createRuntimeModelStatus,
} from '../../../test/fixtures/modelControlPlaneTestFixtures'
import type {CreateOrganizationModelPolicyVersionRequest, ModelPolicyPreview} from '../../../entities/model/api/modelApi'

const organizationId = '00000000-0000-0000-0000-000000000001'
const entry = createModelCatalogEntry()
const runtime = createRuntimeModelStatus()
const initialPolicy = createOrganizationModelPolicy({
    organizationId, enabled: false,
    allowModelKeys: [entry.modelKey], denyModelKeys: [],
    defaultModelKey: entry.modelKey,
})

function preview(): ModelPolicyPreview {
    return {
        organizationId, basePolicyVersion: initialPolicy.version, enabled: true,
        evaluatedAt: '2026-10-24T12:00:00Z',
        runtimeProvider: runtime.provider, runtimeModel: runtime.model,
        automaticOutcome: 'ALLOWED', automaticReason: 'RUNTIME_ONLY_MATCH',
        automaticModelKey: entry.modelKey, executableModelCount: 1,
        wouldLockOutOrganization: false, models: [],
    }
}

function mount(options: {
    onSubmit?: (request: CreateOrganizationModelPolicyVersionRequest) => Promise<void>
    onPreview?: (request: CreateOrganizationModelPolicyVersionRequest) => Promise<ModelPolicyPreview>
} = {}) {
    const onSubmit = options.onSubmit ?? vi.fn(async () => {})
    const onPreview = options.onPreview ?? vi.fn(async () => preview())
    const ui = (provider = runtime.provider, version = entry.version) => (
        <ModelPolicyModal
            policy={initialPolicy}
            catalog={[entry]}
            effectiveCatalog={[{...entry, version}]}
            runtime={{...runtime, provider}}
            organizationId={organizationId}
            organizationName="Тестовая организация"
            pending={false}
            onClose={vi.fn()}
            onPreview={onPreview}
            onSubmit={onSubmit}
        />
    )
    const result = render(ui())
    return {...result, ui, onSubmit, onPreview}
}

describe('Правила моделей — перенос и проверка', () => {
    it('переводит разрешённый ключ в запрещённые только после подтверждения; сбрасывает закрепление', async () => {
        const {onSubmit} = mount()
        const deny = screen.getByRole('combobox', {name: 'Запрещённые модели'})
        fireEvent.focus(deny)
        expect(screen.getByRole('option', {name: new RegExp(entry.modelKey)})).toBeDisabled()
        fireEvent.click(screen.getByRole('button', {name: `Перенести ${entry.modelKey} в запрещённые`}))
        expect(screen.getByText(/список разрешённых станет пустым/)).toBeInTheDocument()
        expect(screen.getByText(/выбор будет сброшен/)).toBeInTheDocument()
        expect(screen.getByRole('button', {name: `Удалить ${entry.modelKey}`})).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', {name: 'Отмена'}))
        expect(screen.getByRole('button', {name: `Удалить ${entry.modelKey}`})).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', {name: `Перенести ${entry.modelKey} в запрещённые`}))
        fireEvent.click(screen.getByRole('button', {name: 'Подтвердить перенос'}))
        expect(screen.queryByRole('list', {name: 'Разрешённые модели: выбранные модели'})).not.toBeInTheDocument()
        expect(screen.getByRole('list', {name: 'Запрещённые модели: выбранные модели'})).toHaveTextContent(entry.modelKey)
        fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            allowModelKeys: [], denyModelKeys: [entry.modelKey], defaultModelKey: null,
        })))
    })

    it('атомарное преобразование не меняет остальные ключи и не допускает 201-й', () => {
        const input = {allowModelKeys: 'mock:a\nmock:b', denyModelKeys: 'mock:c', defaultModelKey: 'mock:a'}
        expect(movePolicyModelKey(input, 'mock:a', 'deny')).toEqual({
            allowModelKeys: 'mock:b', denyModelKeys: 'mock:c\nmock:a', defaultModelKey: '',
        })
        expect(movePolicyModelKey(input, 'mock:no-source', 'deny')).toBe(input)
        const full = {allowModelKeys: 'mock:a', denyModelKeys: Array.from({length: 200}, (_, index) => `mock:d${index}`).join('\n'), defaultModelKey: ''}
        expect(movePolicyModelKey(full, 'mock:a', 'deny')).toBe(full)
    })

    it('показывает критерии проверки и запускает тот же серверный callback', async () => {
        const onPreview = vi.fn(async () => preview())
        mount({onPreview})
        fireEvent.click(screen.getByRole('tab', {name: /Проверка/}))
        expect(screen.getByText('Проверка правил доступа')).toBeInTheDocument()
        expect(screen.getByText('Доступ к моделям')).toBeInTheDocument()
        expect(screen.getByText('Совместимость')).toBeInTheDocument()
        expect(screen.getByText('Стоимость и лимиты')).toBeInTheDocument()
        expect(screen.getAllByText('Требования к данным').length).toBeGreaterThan(0)
        expect(screen.getByText('Проверка ещё не выполнена')).toBeInTheDocument()
        fireEvent.click(screen.getByRole('checkbox', {name: 'Правила выключены'}))
        fireEvent.click(screen.getByRole('button', {name: 'Запустить проверку'}))
        await screen.findByText('Серверная проверка черновика')
        expect(onPreview).toHaveBeenCalledTimes(1)
        expect(screen.getByText(/Положительный результат предварительной оценки/)).toBeInTheDocument()
    })

    it('аннулирует результат при смене провайдера или данных действующего каталога', async () => {
        const onSubmit = vi.fn(async () => {})
        const {rerender, ui} = mount({onSubmit})
        fireEvent.click(screen.getByRole('checkbox', {name: 'Правила выключены'}))
        fireEvent.click(screen.getByRole('tab', {name: /Проверка/}))
        fireEvent.click(screen.getByRole('button', {name: 'Запустить проверку'}))
        await screen.findByText('Серверная проверка черновика')
        rerender(ui('another-provider'))
        expect(screen.getByText(/изменилась конфигурация моделей/)).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
        expect(onSubmit).not.toHaveBeenCalled()
        rerender(ui(runtime.provider, 5))
        expect(screen.queryByText('Серверная проверка черновика')).not.toBeInTheDocument()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it('объясняет NOT_DECLARED как неподтверждённое требование, а не проверенный отказ', () => {
        mount()
        fireEvent.click(screen.getByRole('tab', {name: /Требования к данным/}))
        // Existing metadata remains unchanged, only administrator messaging changes.
        expect(screen.getByText(/Отсутствие сведений не считается подтверждением/)).toBeInTheDocument()
        expect(screen.queryByText(/NOT_DECLARED/)).not.toBeInTheDocument()
    })
})

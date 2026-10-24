// frontend/src/features/models/ui/ModelPolicyModal.tabs.test.tsx
// Сквозные регрессии вкладок, единого черновика, tenant isolation и неизменных API-контрактов.

import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {ModelPolicyModal} from './ModelPolicyModal'
import type {
    CreateOrganizationModelPolicyVersionRequest,
    ModelPolicyPreview,
} from '../../../entities/model/api/modelApi'
import {
    createRuntimeModelStatus,
    createModelCatalogEntry,
    createOrganizationModelPolicy,
} from '../../../test/fixtures/modelControlPlaneTestFixtures'

const runtime = createRuntimeModelStatus()
const catalog = [createModelCatalogEntry()]
const organizationId = '00000000-0000-0000-0000-000000000001'
const otherOrganizationId = '00000000-0000-0000-0000-000000000002'
const policy = createOrganizationModelPolicy()

function view(options: {
    policy?: typeof policy
    organizationId?: string
    onPreview?: (request: CreateOrganizationModelPolicyVersionRequest) => Promise<ModelPolicyPreview>
    onSubmit?: (request: CreateOrganizationModelPolicyVersionRequest) => Promise<void>
    pending?: boolean
} = {}) {
    return <ModelPolicyModal
        policy={options.policy ?? policy}
        catalog={catalog}
        effectiveCatalog={catalog}
        runtime={runtime}
        organizationId={options.organizationId ?? organizationId}
        organizationName="Тестовая организация"
        pending={options.pending ?? false}
        onClose={vi.fn()}
        onPreview={options.onPreview ?? vi.fn(async () => {throw new Error('Неожиданный preview')})}
        onSubmit={options.onSubmit ?? vi.fn(async () => {})}
    />
}

function tab(name: string) {
    return screen.getByRole('tab', {name: new RegExp(name)})
}

describe('ModelPolicyModal: раздельные вкладки и единый черновик', () => {
    it('показывает компактный статус с раскрываемой справкой и без ручки перетаскивания', () => {
        render(view())
        const dialog = screen.getByRole('dialog', {name: 'Правила использования моделей'})
        expect(dialog.querySelector('[data-modal-drag-handle]')).toBeNull()
        expect(dialog.querySelectorAll('[data-modal-resize-handle]')).toHaveLength(8)

        const help = screen.getByText('О настройках').closest('details')!
        expect(help).not.toHaveAttribute('open')
        fireEvent.click(screen.getByText('О настройках'))
        expect(help).toHaveAttribute('open')
        expect(screen.getByText(/Подключённый AI-провайдер задаётся на сервере/))
            .toBeInTheDocument()

        fireEvent.click(screen.getByRole('checkbox', {name: 'Правила выключены'}))
        expect(screen.getByRole('checkbox', {name: 'Правила включены'})).toBeChecked()
        fireEvent.click(tab('Бюджет'))
        expect(screen.getByRole('checkbox', {name: 'Правила включены'})).toBeChecked()
    })

    it('отображает пять логических вкладок, и скрытые панели не доступны для навигации', () => {
        render(view())
        expect(screen.getAllByRole('tab')).toHaveLength(5)
        expect(tab('Доступ')).toHaveAttribute('aria-selected', 'true')
        expect(screen.queryByRole('checkbox', {name: 'Не использовать данные для обучения'}))
            .not.toBeInTheDocument()
        fireEvent.click(tab('Требования к данным'))
        expect(screen.getByRole('checkbox', {name: 'Не использовать данные для обучения'}))
            .toBeVisible()
    })

    it('сохраняет значения всех вкладок при переходах и отправляет единый запрос', async () => {
        const onSubmit = vi.fn(async (_request: CreateOrganizationModelPolicyVersionRequest) => {})
        render(view({onSubmit}))

        fireEvent.click(tab('Лимиты'))
        fireEvent.change(screen.getByPlaceholderText('Например: 32000'), {target: {value: '2048'}})
        fireEvent.change(screen.getByPlaceholderText('Например: 4096'), {target: {value: '512'}})
        fireEvent.click(tab('Бюджет'))
        fireEvent.change(screen.getByLabelText('Бюджет на месяц, USD'), {target: {value: '150.75'}})
        fireEvent.click(tab('Требования к данным'))
        fireEvent.click(screen.getByRole('checkbox', {name: 'Полные данные о стоимости'}))
        fireEvent.click(tab('Лимиты'))
        expect(screen.getByPlaceholderText('Например: 32000')).toHaveValue('2048')
        fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            expectedPreviousVersion: 0,
            enabled: false,
            maxInputTokens: 2048,
            maxOutputTokens: 512,
            requireCompletePricing: true,
        })))
        // Decimal в backend сохраняется как исходная строка (без потерь точности Number).
        expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({monthlyBudgetUsd: '150.75'}))
    })

    it('не отправляет частичный черновик, если в другой вкладке остался ввод модели', () => {
        const onSubmit = vi.fn(async (_request: CreateOrganizationModelPolicyVersionRequest) => {})
        render(view({onSubmit}))
        fireEvent.change(screen.getByRole('combobox', {name: 'Разрешённые модели'}), {
            target: {value: 'незавершённый ввод'},
        })
        fireEvent.click(tab('Бюджет'))
        fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
        expect(tab('Доступ')).toHaveAttribute('aria-selected', 'true')
        expect(screen.getByText(/Завершите добавление модели/)).toBeVisible()
        expect(onSubmit).not.toHaveBeenCalled()
    })

    it('не допускает сохранение с чужими правилами и очищает черновик при смене tenant', () => {
        const onSubmit = vi.fn(async (_request: CreateOrganizationModelPolicyVersionRequest) => {})
        const {rerender} = render(view({onSubmit}))
        fireEvent.click(tab('Лимиты'))
        fireEvent.change(screen.getByPlaceholderText('Например: 32000'), {target: {value: '2048'}})
        rerender(view({
            policy: createOrganizationModelPolicy({organizationId: otherOrganizationId}),
            organizationId: otherOrganizationId,
            onSubmit,
        }))
        expect(tab('Доступ')).toHaveAttribute('aria-selected', 'true')
        fireEvent.click(tab('Лимиты'))
        expect(screen.getByPlaceholderText('Например: 32000')).toHaveValue('')
        fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
        expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            expectedPreviousVersion: 0,
        }))
        expect(onSubmit.mock.calls[0]?.[0].maxInputTokens).toBeNull()
    })

    it('блокирует изменение всех полей во время внешнего сохранения', () => {
        render(view({pending: true}))
        expect(screen.getByRole('checkbox', {name: 'Правила выключены'})).toBeDisabled()
        expect(screen.getByRole('combobox', {name: 'Разрешённые модели'})).toBeDisabled()
        fireEvent.click(tab('Лимиты'))
        expect(screen.getByPlaceholderText('Например: 32000')).toBeDisabled()
        fireEvent.click(tab('Бюджет'))
        expect(screen.getByLabelText('Бюджет на месяц, USD')).toBeDisabled()
        fireEvent.click(tab('Требования к данным'))
        expect(screen.getByRole('checkbox', {name: 'Не использовать данные для обучения'})).toBeDisabled()
    })
})

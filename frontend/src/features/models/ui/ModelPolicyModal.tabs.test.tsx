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
    catalog?: typeof catalog
} = {}) {
    return <ModelPolicyModal
        policy={options.policy ?? policy}
        catalog={options.catalog ?? catalog}
        effectiveCatalog={options.catalog ?? catalog}
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
    it('не создаёт новую версию без изменения правил, в том числе при submit формы', () => {
        const onSubmit = vi.fn(async () => {})
        render(view({policy: createOrganizationModelPolicy({configured: true, version: 1, monthlyBudgetUsd: '15.00'}), onSubmit}))
        const save = screen.getByRole('button', {name: 'Сохранить правила'})
        expect(save).toBeDisabled()
        fireEvent.submit(screen.getByRole('dialog').querySelector('form')!)
        expect(onSubmit).not.toHaveBeenCalled()
        fireEvent.click(tab('Бюджет'))
        fireEvent.change(screen.getByLabelText('Месячный бюджет, USD'), {target: {value: '15.0'}})
        expect(save).toBeDisabled()
        fireEvent.change(screen.getByLabelText('Месячный бюджет, USD'), {target: {value: '15,00'}})
        expect(save).toBeDisabled()
        fireEvent.change(screen.getByLabelText('Месячный бюджет, USD'), {target: {value: '20'}})
        expect(save).toBeEnabled()
        fireEvent.change(screen.getByLabelText('Месячный бюджет, USD'), {target: {value: '15.00'}})
        expect(save).toBeDisabled()
    })

    it('блокирует отправку некорректных чисел даже при смене вкладки и submit формы', () => {
        const onSubmit = vi.fn(async () => {})
        const onPreview = vi.fn(async (): Promise<ModelPolicyPreview> => {throw new Error('Unexpected preview')})
        render(view({onSubmit, onPreview}))
        const save = screen.getByRole('button', {name: 'Сохранить правила'})
        const preview = screen.getByRole('button', {name: 'Проверить правила'})
        fireEvent.click(tab('Лимиты'))
        const input = screen.getByLabelText('Максимум входных токенов')
        fireEvent.change(input, {target: {value: '1e3'}})
        expect(input).toHaveAttribute('aria-invalid', 'true')
        expect(screen.getByText(/введите целое число от 1 до/)).toBeVisible()
        expect(save).toBeDisabled()
        expect(preview).toBeDisabled()
        fireEvent.click(tab('Бюджет'))
        const budget = screen.getByLabelText('Месячный бюджет, USD')
        fireEvent.change(budget, {target: {value: '1,2.3'}})
        expect(budget).toHaveAttribute('aria-invalid', 'true')
        expect(screen.getByText(/укажите неотрицательное число/)).toBeVisible()
        fireEvent.submit(screen.getByRole('dialog').querySelector('form')!)
        expect(onSubmit).not.toHaveBeenCalled()
        expect(onPreview).not.toHaveBeenCalled()
        expect(tab('Лимиты')).toHaveAttribute('aria-selected', 'true')
        fireEvent.change(screen.getByLabelText('Максимум входных токенов'), {target: {value: '2147483647'}})
        expect(screen.getByText(/Выше лимита подключения/)).toBeVisible()
        expect(save).toBeDisabled()
        fireEvent.click(tab('Бюджет'))
        fireEvent.change(screen.getByLabelText('Месячный бюджет, USD'), {target: {value: '0,05'}})
        expect(save).toBeEnabled()
        expect(preview).toBeEnabled()
    })

    it('передаёт точные денежные строки с нормализованным разделителем', async () => {
        const onSubmit = vi.fn(async () => {})
        render(view({onSubmit}))
        fireEvent.click(tab('Бюджет'))
        fireEvent.change(screen.getByLabelText('Лимит на один запрос, USD'), {target: {value: '0,000000000001'}})
        fireEvent.change(screen.getByLabelText('Месячный бюджет, USD'), {target: {value: '999999999999999999,123456789012'}})
        fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            maxRequestCostUsd: '0.000000000001',
            monthlyBudgetUsd: '999999999999999999.123456789012',
        })))
    })

    it('отличает нулевое финансовое ограничение от пустого поля', async () => {
        const onSubmit = vi.fn(async () => {})
        render(view({onSubmit}))
        fireEvent.click(tab('Бюджет'))
        fireEvent.change(screen.getByLabelText('Лимит на один запрос, USD'), {target: {value: '0'}})
        fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
        await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            maxRequestCostUsd: '0',
            monthlyBudgetUsd: null,
        })))
    })

    it('показывает фактические лимиты и пояснения к выбранным требованиям', () => {
        render(view({catalog: [{...catalog[0]!, pricingComplete: false, retentionStatus: 'NOT_DECLARED'}]}))
        fireEvent.click(tab('Лимиты'))
        expect(screen.getByLabelText('Максимум входных токенов')).toHaveAttribute('placeholder', 'Без дополнительного лимита')
        expect(screen.getByText(`Лимит подключения: ${runtime.maxInputTokens.toLocaleString('ru-RU').replace(/\s/g, ' ')}`)).toBeVisible()
        expect(screen.getByText(`Лимит подключения: ${runtime.maxOutputTokens.toLocaleString('ru-RU').replace(/\s/g, ' ')}`)).toBeVisible()
        fireEvent.click(tab('Требования'))
        fireEvent.click(screen.getByRole('checkbox', {name: 'Полные сведения о стоимости'}))
        fireEvent.click(screen.getByRole('checkbox', {name: 'Нулевое хранение данных у провайдера'}))
        expect(screen.getByText('Сведения о стоимости неполные')).toBeVisible()
        expect(screen.getByText('Отсутствие хранения данных не подтверждено')).toBeVisible()
    })
    it('показывает компактный статус с раскрываемой справкой и ручкой перетаскивания', () => {
        render(view())
        const dialog = screen.getByRole('dialog', {name: 'Настройка правил использования моделей'})
        expect(dialog.querySelector('[data-modal-drag-handle="true"]')).toBeInTheDocument()
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

    it('перемещает окно за заголовок и сохраняет введённый бюджет', () => {
        vi.stubGlobal('innerWidth', 1920)
        vi.stubGlobal('innerHeight', 1200)
        try {
            render(view())
            fireEvent.click(tab('Бюджет'))
            fireEvent.change(screen.getByLabelText('Месячный бюджет, USD'), {target: {value: '20'}})
            const dialog = screen.getByRole('dialog', {name: 'Настройка правил использования моделей'})
            const header = screen.getByRole('heading', {name: 'Настройка правил использования моделей'})
            const left = Number.parseFloat(dialog.style.left)
            const top = Number.parseFloat(dialog.style.top)
            fireEvent.pointerDown(header, {pointerId: 1, button: 0, clientX: 800, clientY: 250})
            fireEvent.pointerMove(header, {pointerId: 1, clientX: 880, clientY: 290})
            fireEvent.pointerUp(header, {pointerId: 1})
            expect(dialog.style.left).toBe(`${left + 80}px`)
            expect(dialog.style.top).toBe(`${top + 40}px`)
            expect(screen.getByLabelText('Месячный бюджет, USD')).toHaveValue('20')
            expect(document.body).not.toHaveClass('modal-dragging')
        } finally {
            vi.unstubAllGlobals()
        }
    })

    it('отображает пять логических вкладок, и скрытые панели не доступны для навигации', () => {
        render(view())
        expect(screen.getAllByRole('tab')).toHaveLength(5)
        expect(tab('Доступ')).toHaveAttribute('aria-selected', 'true')
        expect(screen.queryByRole('checkbox', {name: 'Запрет использования данных для обучения'}))
            .not.toBeInTheDocument()
        fireEvent.click(tab('Требования'))
        expect(screen.getByRole('checkbox', {name: 'Запрет использования данных для обучения'}))
            .toBeVisible()
    })

    it('сохраняет значения всех вкладок при переходах и отправляет единый запрос', async () => {
        const onSubmit = vi.fn(async (_request: CreateOrganizationModelPolicyVersionRequest) => {})
        render(view({onSubmit}))

        fireEvent.click(tab('Лимиты'))
        fireEvent.change(screen.getByLabelText('Максимум входных токенов'), {target: {value: '2048'}})
        fireEvent.change(screen.getByLabelText('Максимум выходных токенов'), {target: {value: '512'}})
        fireEvent.click(tab('Бюджет'))
        fireEvent.change(screen.getByLabelText('Месячный бюджет, USD'), {target: {value: '150.75'}})
        fireEvent.click(tab('Требования'))
        fireEvent.click(screen.getByRole('checkbox', {name: 'Полные сведения о стоимости'}))
        fireEvent.click(tab('Лимиты'))
        expect(screen.getByLabelText('Максимум входных токенов')).toHaveValue('2048')
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
        fireEvent.change(screen.getByLabelText('Максимум входных токенов'), {target: {value: '2048'}})
        rerender(view({
            policy: createOrganizationModelPolicy({organizationId: otherOrganizationId}),
            organizationId: otherOrganizationId,
            onSubmit,
        }))
        expect(tab('Доступ')).toHaveAttribute('aria-selected', 'true')
        fireEvent.click(tab('Лимиты'))
        expect(screen.getByLabelText('Максимум входных токенов')).toHaveValue('')
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
        expect(screen.getByLabelText('Максимум входных токенов')).toBeDisabled()
        fireEvent.click(tab('Бюджет'))
        expect(screen.getByLabelText('Месячный бюджет, USD')).toBeDisabled()
        fireEvent.click(tab('Требования'))
        expect(screen.getByRole('checkbox', {name: 'Запрет использования данных для обучения'})).toBeDisabled()
    })
})

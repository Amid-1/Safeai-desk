// frontend/src/features/models/ui/ModelPolicyModal.rubBudget.test.tsx
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {ModelPolicyModal} from './ModelPolicyModal'
import {getRubBudgetQuote, moscowBudgetDate} from '../../../entities/model/api/modelApi'
import {createRuntimeModelStatus, createModelCatalogEntry, createOrganizationModelPolicy, createPolicyPreview} from '../../../test/fixtures/modelControlPlaneTestFixtures'

vi.mock('../../../entities/model/api/modelApi', async importOriginal => ({
    ...await importOriginal<typeof import('../../../entities/model/api/modelApi')>(),
    getRubBudgetQuote: vi.fn(),
}))

const quote = () => ({rubPerUsd: '90.0000', quoteDate: moscowBudgetDate(),
    effectiveDate: moscowBudgetDate(), fetchedAt: new Date().toISOString(), source: 'CBR' as const})

function open(policy = createOrganizationModelPolicy({enabled: false}), onSubmit = vi.fn(async () => {})) {
    const catalog = [createModelCatalogEntry()]
    render(<ModelPolicyModal policy={policy} catalog={catalog} effectiveCatalog={catalog}
        runtime={createRuntimeModelStatus()} organizationId={policy.organizationId}
        organizationName="Тест" pending={false} onClose={vi.fn()}
        onPreview={async request => createPolicyPreview(request, policy.organizationId, createRuntimeModelStatus())} onSubmit={onSubmit}/>)
    fireEvent.click(screen.getByRole('tab', {name: 'Бюджет'}))
    return onSubmit
}

describe('RUB budget editing', () => {
    it('shows a read-only RUB reference without switching the USD draft or adding FX metadata', async () => {
        vi.mocked(getRubBudgetQuote).mockResolvedValueOnce(quote())
        const submit = open(createOrganizationModelPolicy({enabled: false, monthlyBudgetUsd: '10'}))
        const input = screen.getByLabelText('Месячный бюджет, USD')
        fireEvent.click(screen.getByRole('button', {name: 'Показать эквивалент в RUB'}))
        expect(await screen.findByText('Месячный бюджет: 900 RUB')).toBeVisible()
        expect(input).toHaveValue('10')
        expect(screen.getByRole('button', {name: 'USD · Доллары'})).toHaveAttribute('aria-pressed', 'true')
        fireEvent.change(input, {target: {value: '20'}})
        expect(screen.getByText('Месячный бюджет: 1800 RUB')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'Проверить правила'}))
        await waitFor(() => expect(screen.getByRole('button', {name: 'Сохранить правила'})).toBeEnabled())
        fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
        await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({monthlyBudgetUsd: '20'})))
        expect(submit).not.toHaveBeenCalledWith(expect.objectContaining({rubBudget: expect.anything()}))
    })
    it('switches without changing exact ceiling and submits amounts without duplicate USD fields', async () => {
        vi.mocked(getRubBudgetQuote).mockResolvedValueOnce(quote())
        const submit = open(createOrganizationModelPolicy({enabled: false, monthlyBudgetUsd: '10'}))
        fireEvent.click(screen.getByRole('button', {name: 'RUB · Рубли'}))
        const input = await screen.findByLabelText('Месячный бюджет, RUB')
        expect(input).toHaveValue('900')
        expect(screen.getByText('1 USD = 90 RUB')).toBeVisible()
        fireEvent.change(input, {target: {value: '10000,00'}})
        expect(screen.getByText('111,111111111111 USD')).toBeVisible()
        fireEvent.click(screen.getByRole('button', {name: 'Проверить правила'}))
        await waitFor(() => expect(screen.getByRole('button', {name: 'Сохранить правила'})).toBeEnabled())
        fireEvent.click(screen.getByRole('button', {name: 'Сохранить правила'}))
        await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({
            monthlyBudgetUsd: null, maxRequestCostUsd: null,
            rubBudget: expect.objectContaining({monthlyBudgetRub: '10000.00', maxRequestCostRub: null, rubPerUsd: '90.0000'}),
        })))
    })

    it('retains USD if official source fails', async () => {
        vi.mocked(getRubBudgetQuote).mockRejectedValueOnce(new Error('Нет курса'))
        open(createOrganizationModelPolicy({enabled: false, monthlyBudgetUsd: '10'}))
        fireEvent.click(screen.getByRole('button', {name: 'RUB · Рубли'}))
        await screen.findByRole('alert')
        expect(screen.getByLabelText('Месячный бюджет, USD')).toHaveValue('10')
    })

    it('historical amounts are editable only after refreshing the quote', () => {
        open(createOrganizationModelPolicy({enabled: false, monthlyBudgetUsd: '10', rubBudget: {
            ...quote(), quoteDate: '2026-01-01', effectiveDate: '2026-01-01', fetchedAt: '2026-01-01T01:00:00Z',
            maxRequestCostRub: null, monthlyBudgetRub: '900',
        }}))
        expect(screen.getByText('Сохранённый курс')).toBeVisible()
        fireEvent.change(screen.getByLabelText('Месячный бюджет, RUB'), {target: {value: '901'}})
        expect(screen.getByText('Для изменения рублёвого бюджета получите актуальный курс.')).toBeVisible()
        expect(screen.getByRole('button', {name: 'Сохранить правила'})).toBeDisabled()
        expect(screen.getByRole('button', {name: 'Проверить правила'})).toBeDisabled()
    })
})

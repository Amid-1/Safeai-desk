// frontend/src/features/models/ui/ModelCatalogVersionModal.production.test.tsx
// ============================================================
// frontend/src/features/models/ui/ModelCatalogVersionModal.production.test.tsx
// ============================================================
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ComponentProps } from 'react'
import { ModelCatalogVersionModal } from './ModelCatalogVersionModal'
import { createModelCatalogEntry, createRuntimeModelStatus } from '../../../test/fixtures/modelControlPlaneTestFixtures'
import { getRubBudgetQuote, moscowBudgetDate } from '../../../entities/model/api/modelApi'
import { ApiError } from '../../../shared/api/http'

vi.mock('../../../entities/model/api/modelApi', async (importOriginal) => ({
    ...await importOriginal<typeof import('../../../entities/model/api/modelApi')>(),
    getRubBudgetQuote: vi.fn(),
}))

function selectTab(name: string) {
    fireEvent.click(screen.getByRole('tab', {name}))
}

function createProps(overrides: Partial<ComponentProps<typeof ModelCatalogVersionModal>> = {}) {
    return {
        base: null,
        runtime: createRuntimeModelStatus(),
        catalogByKey: new Map(),
        pending: false,
        onClose: vi.fn(),
        onSubmit: vi.fn().mockResolvedValue(undefined),
        ...overrides,
    } satisfies ComponentProps<typeof ModelCatalogVersionModal>
}

function submitForm() {
    const form = document.querySelector('form.models-catalog-form')
    if (!form) throw new Error('Форма каталога не найдена')
    fireEvent.submit(form)
}

describe('ModelCatalogVersionModal — production regression', () => {
    beforeEach(() => {
        vi.mocked(getRubBudgetQuote).mockReset().mockResolvedValue({
            rubPerUsd: '82.5143', quoteDate: moscowBudgetDate(),
            effectiveDate: moscowBudgetDate(), fetchedAt: new Date().toISOString(), source: 'CBR',
        })
    })
    afterEach(() => { vi.restoreAllMocks() })

    it('создаёт неизменяемую первую версию с параметрами текущего Runtime', async () => {
        const props = createProps()
        render(<ModelCatalogVersionModal {...props} />)
        submitForm()
        await waitFor(() => expect(props.onSubmit).toHaveBeenCalledOnce())
        expect(props.onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            modelKey: 'mock:mock-safeai',
            provider: 'mock', providerModelId: 'mock-safeai',
            expectedPreviousVersion: 0, maxInputTokens: 64_000,
            maxOutputTokens: 2_048, pricingStatus: 'FREE', pricingComplete: true,
        }))
    })

    it('не выводит сырое исключение сохранения каталога', async () => {
        const props = createProps({ onSubmit: vi.fn().mockRejectedValue(new Error('SQL diagnostic private value')) })
        render(<ModelCatalogVersionModal {...props} />)
        submitForm()
        expect(await screen.findByText('Не удалось сохранить модель.')).toBeInTheDocument()
        expect(screen.queryByText('SQL diagnostic private value')).not.toBeInTheDocument()
    })

    it('использует optimistic version и блокирует изменение ключа версии существующей модели', async () => {
        const entry = createModelCatalogEntry({ version: 8 })
        const props = createProps({ base: entry, catalogByKey: new Map([[entry.modelKey, entry]]) })
        render(<ModelCatalogVersionModal {...props} />)
        expect(screen.getByRole('textbox', { name: /Ключ модели/ })).toBeDisabled()
        expect(screen.getByText(/После сохранения появится версия 9/)).toBeInTheDocument()
        submitForm()
        await waitFor(() => expect(props.onSubmit).toHaveBeenCalledOnce())
        expect(props.onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            modelKey: entry.modelKey, expectedPreviousVersion: 8,
        }))
    })

    it('объясняет новую версию уже зарегистрированного ключа в режиме создания', () => {
        const existing = createModelCatalogEntry({ version: 4 })
        render(<ModelCatalogVersionModal {...createProps({ catalogByKey: new Map([[existing.modelKey, existing]]) })} />)
        expect(screen.getByRole('heading', { name: 'Новая версия: mock-safeai' })).toBeInTheDocument()
        expect(screen.getByText(/После сохранения появится версия 5/)).toBeInTheDocument()
        fireEvent.change(screen.getByRole('textbox', { name: /Ключ модели/ }), { target: { value: 'mock:new-model' } })
        expect(screen.getByRole('heading', { name: 'Добавление модели в каталог' })).toBeInTheDocument()
        expect(screen.getByText(/После сохранения появится первая версия модели/)).toBeInTheDocument()
    })

    it('не закрывает изменённый черновик без явного подтверждения', () => {
        const props = createProps()
        render(<ModelCatalogVersionModal {...props} />)
        fireEvent.change(screen.getByLabelText('Название'), { target: { value: 'Новая метка' } })
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
        fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
        expect(confirm).toHaveBeenCalledWith('Отменить несохранённые изменения версии каталога?')
        expect(props.onClose).not.toHaveBeenCalled()
        confirm.mockReturnValue(true)
        fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
        expect(props.onClose).toHaveBeenCalledOnce()
    })

    it('закрывает чистый черновик без confirm', () => {
        const props = createProps()
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
        render(<ModelCatalogVersionModal {...props} />)
        fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
        expect(confirm).not.toHaveBeenCalled()
        expect(props.onClose).toHaveBeenCalledOnce()
    })

    it('во время pending не позволяет закрыть форму и повторить сохранение', () => {
        const props = createProps({ pending: true })
        render(<ModelCatalogVersionModal {...props} />)
        expect(screen.getByRole('button', { name: 'Отмена' })).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Сохраняем...' })).toBeDisabled()
        submitForm()
        expect(props.onSubmit).not.toHaveBeenCalled()
        expect(props.onClose).not.toHaveBeenCalled()
    })

    it('отсекает два submit до рендера и запрещает закрытие во время выполняющегося POST', async () => {
        let finish: (() => void) | undefined
        const submit = vi.fn(() => new Promise<void>((resolve) => { finish = resolve }))
        const props = createProps({ onSubmit: submit })
        render(<ModelCatalogVersionModal {...props} />)
        submitForm()
        submitForm()
        expect(submit).toHaveBeenCalledOnce()
        expect(screen.getByRole('button', { name: 'Отмена' })).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Сохраняем...' })).toBeDisabled()
        await act(async () => { finish?.() })
        await waitFor(() => expect(screen.getByRole('button', { name: 'Сохранить версию' })).toBeEnabled())
    })

    it('показывает ошибку валидации и не вызывает API', () => {
        const props = createProps()
        render(<ModelCatalogVersionModal {...props} />)
        selectTab('Лимиты')
        fireEvent.change(screen.getByLabelText('Входные токены, максимум'), { target: { value: '0' } })
        selectTab('Основные данные')
        submitForm()
        expect(props.onSubmit).not.toHaveBeenCalled()
        expect(screen.getByText(/Максимум входных токенов/)).toBeInTheDocument()
        expect(screen.getByRole('tab', {name: 'Лимиты'})).toHaveAttribute('aria-selected', 'true')
    })

    it('показывает ошибку POST и позволяет повторить после завершённой ошибки', async () => {
        const submit = vi.fn().mockRejectedValueOnce(new ApiError('Конфликт версии', { code: 'CONFLICT' }, 409)).mockResolvedValueOnce(undefined)
        const props = createProps({ onSubmit: submit })
        render(<ModelCatalogVersionModal {...props} />)
        submitForm()
        expect(await screen.findByText('Данные были изменены. Обновите страницу.')).toBeInTheDocument()
        submitForm()
        await waitFor(() => expect(submit).toHaveBeenCalledTimes(2))
    })

    it('проверяет ограничения, JSON тарификации и не отправляет некорректный черновик', () => {
        const props = createProps()
        render(<ModelCatalogVersionModal {...props} />)
        selectTab('Тарификация')
        fireEvent.change(screen.getByLabelText(/JSON-параметры/), {
            target: { value: 'не JSON' },
        })
        submitForm()
        expect(props.onSubmit).not.toHaveBeenCalled()
        expect(screen.getByRole('alert')).toHaveTextContent(/JSON-объектом/)
        expect(document.querySelector('details.models-form__advanced')).toHaveAttribute('open')
    })

    it('сохраняет черновик без потерь при изменении всех основных данных', async () => {
        const props = createProps()
        render(<ModelCatalogVersionModal {...props} />)
        fireEvent.change(screen.getByRole('textbox', { name: /Ключ модели/ }), {
            target: { value: 'vendor:next' },
        })
        fireEvent.change(screen.getByLabelText('Название'), { target: { value: 'Новая модель' } })
        fireEvent.change(screen.getByLabelText('Провайдер'), { target: { value: 'vendor' } })
        fireEvent.change(screen.getByLabelText('ID модели у провайдера'), {
            target: { value: 'next' },
        })
        selectTab('Лимиты')
        fireEvent.change(screen.getByLabelText('Входные токены, максимум'), {
            target: { value: '32000' },
        })
        fireEvent.change(screen.getByLabelText('Выходные токены, максимум'), {
            target: { value: '4096' },
        })
        submitForm()
        await waitFor(() => expect(props.onSubmit).toHaveBeenCalledOnce())
        expect(props.onSubmit).toHaveBeenCalledWith(expect.objectContaining({
            modelKey: 'vendor:next', displayName: 'Новая модель',
            provider: 'vendor', providerModelId: 'next',
            maxInputTokens: 32_000, maxOutputTokens: 4_096,
            expectedPreviousVersion: 0,
        }))
    })

    it('различает неизвестную и нулевую стоимость при переключении тарификации', async () => {
        const props = createProps()
        render(<ModelCatalogVersionModal {...props} />)
        selectTab('Тарификация')
        fireEvent.change(screen.getByLabelText('Статус стоимости'), { target: { value: 'UNPRICED' } })
        expect(screen.getByLabelText('Все варианты тарификации известны')).toBeDisabled()
        submitForm()
        await waitFor(() => expect(props.onSubmit).toHaveBeenCalledOnce())
        expect(props.onSubmit).toHaveBeenLastCalledWith(expect.objectContaining({
            pricingStatus: 'UNPRICED', pricingComplete: false, inputUsdPer1mTokens: null,
            outputUsdPer1mTokens: null,
        }))
    })

    it('сохраняет поля всех пяти секций и не разрешает IMAGE как выходную модальность', () => {
        render(<ModelCatalogVersionModal {...createProps()} />)
        for (const heading of [
            'Основные данные', 'Лимиты', 'Безопасность данных',
            'Возможности модели', 'Тарификация',
        ]) {
            selectTab(heading)
            expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
            expect(screen.getAllByRole('tabpanel')).toHaveLength(1)
        }
        const fieldsets = [...document.querySelectorAll('fieldset')]
        const output = fieldsets.find((item) => item.querySelector('legend')?.textContent === 'Выходные данные')
        expect(output).toBeDefined()
        expect(output?.textContent).not.toContain('Изображения')
        expect(screen.getByText(/Неизвестная стоимость не считается нулевой/)).toBeInTheDocument()
    })

    it('сохраняет точные USD-тарифы, показывает RUB только в интерфейсе и не загружает курс до вкладки', async () => {
        const base = createModelCatalogEntry({inputUsdPer1mTokens: '0.000000000000', outputUsdPer1mTokens: '0.000000000000'})
        const props = createProps({base, catalogByKey: new Map([[base.modelKey, base]])})
        render(<ModelCatalogVersionModal {...props} />)
        expect(getRubBudgetQuote).not.toHaveBeenCalled()
        selectTab('Тарификация')
        expect(await screen.findByText('1 USD = 82,5143 RUB')).toBeInTheDocument()
        expect(screen.getByLabelText('Входные токены, USD / 1 млн')).toHaveValue('0')
        expect(document.querySelector('details.models-form__advanced')).not.toHaveAttribute('open')
        fireEvent.change(screen.getByLabelText('Статус стоимости'), {target: {value: 'CONFIGURED'}})
        fireEvent.change(screen.getByLabelText('Версия тарифа'), {target: {value: 'new-rate'}})
        fireEvent.change(screen.getByLabelText('Входные токены, USD / 1 млн'), {target: {value: '2,50'}})
        fireEvent.change(screen.getByLabelText('Выходные токены, USD / 1 млн'), {target: {value: '10'}})
        expect(screen.getByText('≈ 206,29 ₽')).toBeInTheDocument()
        expect(screen.getByText('≈ 825,14 ₽')).toBeInTheDocument()
        selectTab('Безопасность данных')
        expect(screen.queryByRole('combobox', {name: 'Статус стоимости'})).not.toBeInTheDocument()
        selectTab('Тарификация')
        expect(screen.getByLabelText('Входные токены, USD / 1 млн')).toHaveValue('2.50')
        submitForm()
        await waitFor(() => expect(props.onSubmit).toHaveBeenCalledOnce())
        const payload = vi.mocked(props.onSubmit).mock.calls[0]![0]
        expect(payload).toMatchObject({inputUsdPer1mTokens: '2.50', outputUsdPer1mTokens: '10'})
        expect(Object.keys(payload).some(key => /rub|quote|rate/i.test(key))).toBe(false)
    })

    it('не блокирует сохранение USD, если курс недоступен, и даёт обновить курс', async () => {
        vi.mocked(getRubBudgetQuote).mockRejectedValueOnce(new Error('offline'))
        const props = createProps()
        render(<ModelCatalogVersionModal {...props} />)
        selectTab('Тарификация')
        expect(await screen.findByText('Курс недоступен. Тарифы можно сохранить в USD.')).toBeInTheDocument()
        submitForm()
        await waitFor(() => expect(props.onSubmit).toHaveBeenCalledOnce())
        fireEvent.click(screen.getByRole('button', {name: 'Обновить курс'}))
        expect(await screen.findByText('1 USD = 82,5143 RUB')).toBeInTheDocument()
    })

    it('не применяет вчерашний курс и отменяет запрос при закрытии', async () => {
        vi.mocked(getRubBudgetQuote).mockResolvedValueOnce({
            rubPerUsd: '82.5143', quoteDate: '2000-01-01', effectiveDate: '2000-01-01',
            fetchedAt: '2000-01-01T12:00:00Z', source: 'CBR',
        })
        const {unmount} = render(<ModelCatalogVersionModal {...createProps()} />)
        selectTab('Тарификация')
        expect(await screen.findByText('Курс недоступен. Тарифы можно сохранить в USD.')).toBeInTheDocument()
        expect(screen.queryByText('1 USD = 82,5143 RUB')).not.toBeInTheDocument()
        vi.mocked(getRubBudgetQuote).mockImplementationOnce(() => new Promise(() => {}))
        fireEvent.click(screen.getByRole('button', {name: 'Обновить курс'}))
        const signal = vi.mocked(getRubBudgetQuote).mock.calls.at(-1)?.[0]
        expect(signal?.aborted).toBe(false)
        unmount()
        expect(signal?.aborted).toBe(true)
    })

    it('переключает вкладки клавиатурой без потери черновика', () => {
        render(<ModelCatalogVersionModal {...createProps()} />)
        const tab = screen.getByRole('tab', {name: 'Основные данные'})
        fireEvent.change(screen.getByLabelText('Название'), {target: {value: 'Моя модель'}})
        fireEvent.keyDown(tab, {key: 'End'})
        expect(screen.getByRole('tab', {name: 'Возможности модели'})).toHaveFocus()
        fireEvent.keyDown(screen.getByRole('tab', {name: 'Возможности модели'}), {key: 'Home'})
        expect(tab).toHaveFocus()
        expect(screen.getByLabelText('Название')).toHaveValue('Моя модель')
    })

    it('не закрывает форму при нажатии на фон', () => {
        const props = createProps()
        render(<ModelCatalogVersionModal {...props} />)
        const backdrop = document.querySelector('.modal-backdrop')
        expect(backdrop).not.toBeNull()
        if (backdrop) fireEvent.pointerDown(backdrop, { pointerId: 1, button: 0 })
        expect(props.onClose).not.toHaveBeenCalled()
    })
})

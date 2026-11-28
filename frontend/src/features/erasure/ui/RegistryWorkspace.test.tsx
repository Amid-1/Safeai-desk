// frontend/src/features/erasure/ui/RegistryWorkspace.test.tsx
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {MemoryRouter, Outlet, Route, Routes} from 'react-router-dom'
import {ApiError} from '../../../shared/api/http'
import {
    loadRegistry,
    loadRegistrySummary,
    type Capabilities,
    type RegistryItem,
    type RegistryPage,
    type RegistrySummary
} from '../api/workspaceApi'
import RegistryWorkspace from './RegistryWorkspace'

vi.mock('../api/workspaceApi', () => ({loadRegistry: vi.fn(), loadRegistrySummary: vi.fn()}))
const list = vi.mocked(loadRegistry)
const summary = vi.mocked(loadRegistrySummary)
const platform: Capabilities = {
    platform: true,
    tenantRegistry: false,
    personalRequests: false,
    reviewQueue: false,
    requestsEnabled: true,
    approvalsEnabled: false
}
const tenant: Capabilities = {...platform, platform: false, tenantRegistry: true, personalRequests: true}
const item: RegistryItem = {
    requestId: '4cc9a902-47a2-46f7-9594-49261e6dd90e',
    organization: 'Demo Company',
    requester: null,
    resourceType: 'CHAT',
    state: 'PENDING',
    intakeState: 'SUBMITTED',
    reviewState: 'PENDING',
    reviewVisible: true,
    holdActive: false,
    updatedAt: '2026-11-25T00:00:00Z'
}
const empty: RegistryPage = {items: [], page: 0, pageSize: 25, hasNext: false}
const globalStats: RegistrySummary = {
    scope: 'PLATFORM',
    total: 1,
    registered: 1,
    withdrawn: 0,
    drafts: null,
    reviewed: 1,
    pending: 1,
    needsInformation: 0,
    held: 0
}
const tenantStats: RegistrySummary = {
    scope: 'TENANT',
    total: 1,
    registered: 1,
    withdrawn: 0,
    drafts: 0,
    reviewed: null,
    pending: null,
    needsInformation: null,
    held: null
}

function mount(capabilities: Capabilities | null = platform, path = '/registry') {
    return render(<MemoryRouter initialEntries={[path]}><Routes><Route
        element={<Outlet context={{capabilities, loading: false, failed: false}}/>}>
        <Route path="/registry" element={<RegistryWorkspace/>}/>
    </Route></Routes></MemoryRouter>)
}

function selectState(state: string) {
    fireEvent.change(screen.getByRole('combobox', {name: 'Состояние заявки'}), {target: {value: state}})
}

describe('Registry workspace R2.5.4', () => {
    beforeEach(() => {
        vi.resetAllMocks();
        list.mockResolvedValue(empty);
        summary.mockResolvedValue(globalStats)
    })
    it('uses server metrics and independently renders empty review filters without fake errors', async () => {
        list.mockResolvedValueOnce({...empty, items: [item]})
        mount()
        expect(await screen.findByRole('link', {name: 'Открыть карточку'})).toBeInTheDocument()
        const metrics = screen.getByRole('region', {name: 'Сводка реестра'})
        expect(within(metrics).getByRole('button', {name: 'Всего в реестре 1'})).toBeInTheDocument()
        expect(within(metrics).getByRole('button', {name: 'С удержаниями 0'})).toBeInTheDocument()
        expect(screen.getByText('Зарегистрирована')).toBeInTheDocument()
        expect(screen.getByText('Ожидает рассмотрения')).toBeInTheDocument()
        expect(screen.getByText('Рассмотрение заявок отключено')).toBeInTheDocument()
        selectState('NEEDS_INFORMATION')
        expect(await screen.findByText('Заявок не найдено.')).toBeInTheDocument()
        expect(screen.getByRole('heading', {name: 'Состояние: Нужно уточнение'})).toBeInTheDocument()
        expect(screen.queryByRole('button', {name: 'Вперёд'})).not.toBeInTheDocument()
        expect(screen.queryByRole('button', {name: 'Назад'})).not.toBeInTheDocument()
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
        expect(screen.queryByRole('link', {name: 'Открыть карточку'})).not.toBeInTheDocument()
        expect(list).toHaveBeenLastCalledWith(false, 'NEEDS_INFORMATION', '', 0, expect.any(AbortSignal))
        expect(summary).toHaveBeenCalledTimes(1)
        fireEvent.click(screen.getByRole('button', {name: 'Сбросить фильтры'}))
        await waitFor(() => expect(list).toHaveBeenLastCalledWith(false, 'ALL', '', 0, expect.any(AbortSignal)))
    })
    it('keeps tenant filters and metrics separate from platform review data', async () => {
        summary.mockResolvedValue(tenantStats)
        list.mockResolvedValue({
            ...empty,
            items: [{...item, requester: 'Owner', state: 'SUBMITTED', reviewState: null, reviewVisible: false}]
        })
        mount(tenant, '/registry?state=HELD')
        expect(await screen.findByText('Owner')).toBeInTheDocument()
        const select = screen.getByRole('combobox', {name: 'Состояние заявки'})
        expect(select).toHaveValue('ALL')
        expect(within(select).getAllByRole('option').map(option => option.getAttribute('value'))).toEqual(['ALL', 'DRAFT', 'SUBMITTED', 'WITHDRAWN'])
        expect(screen.queryByRole('columnheader', {name: 'Рассмотрение'})).not.toBeInTheDocument()
        expect(screen.queryByRole('columnheader', {name: 'Удержание'})).not.toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Черновики 0'})).toBeInTheDocument()
    })
    it('does not fetch until server capabilities authorize the workspace', () => {
        mount(null)
        expect(screen.getByText('Реестр сейчас недоступен.')).toBeInTheDocument()
        expect(list).not.toHaveBeenCalled()
        expect(summary).not.toHaveBeenCalled()
    })
    it.each([[403, 'FORBIDDEN', 'Недостаточно прав'], [500, 'INTERNAL_ERROR', 'Сервис временно недоступен'], [0, 'INVALID_RESPONSE', 'Сервер вернул некорректный ответ']] as const)('distinguishes HTTP/contract failure %s from a genuine empty result', async (status, code, message) => {
        list.mockRejectedValue(new ApiError('PRIVATE SQL TRACE', {code, requestId: 'safe-request-id'}, status))
        mount()
        const alert = await screen.findByRole('alert')
        expect(alert).toHaveTextContent(message)
        expect(alert).toHaveTextContent('safe-request-id')
        expect(alert).not.toHaveTextContent('PRIVATE SQL TRACE')
        expect(screen.queryByText('Заявок не найдено.')).not.toBeInTheDocument()
    })
    it('does not display mismatched summary as zero or leak unexpected review rows', async () => {
        summary.mockResolvedValue(globalStats)
        list.mockResolvedValue({...empty, items: [item]})
        mount(tenant)
        await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(2))
        expect(screen.getByText('Область статистики не соответствует доступу.')).toBeInTheDocument()
        expect(screen.getByText('Область ответа не соответствует доступу. Данные не показаны.')).toBeInTheDocument()
        expect(screen.queryByText('Demo Company')).not.toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Всего в реестре —'})).toBeInTheDocument()
    })
    it('keeps the current filter after a stale request resolves', async () => {
        let finish: (page: RegistryPage) => void = () => undefined
        list.mockImplementationOnce(() => new Promise(resolve => {
            finish = resolve
        }))
        mount()
        await waitFor(() => expect(list).toHaveBeenCalledTimes(1))
        const signal = list.mock.calls[0][4]
        selectState('REJECTED')
        expect(await screen.findByText('Заявок не найдено.')).toBeInTheDocument()
        expect(signal?.aborted).toBe(true)
        finish({...empty, items: [item]})
        await waitFor(() => expect(screen.queryByText('Demo Company')).not.toBeInTheDocument())
    })
    it('keeps next and previous navigation when additional pages exist', async () => {
        list.mockResolvedValueOnce({...empty, items: [item], hasNext: true})
        list.mockResolvedValueOnce({...empty, page: 1})
        mount()
        await screen.findByRole('link', {name: 'Открыть карточку'})
        expect(screen.getByRole('button', {name: 'Назад'})).toBeDisabled()
        fireEvent.click(screen.getByRole('button', {name: 'Вперёд'}))
        await screen.findByText('Заявок не найдено.')
        expect(screen.getByRole('button', {name: 'Назад'})).toBeEnabled()
        expect(screen.getByRole('button', {name: 'Вперёд'})).toBeDisabled()
        expect(list).toHaveBeenLastCalledWith(false, 'ALL', '', 1, expect.any(AbortSignal))
    })
    it('keeps a way back from an empty later page', async () => {
        list.mockResolvedValue({...empty, page: 2})
        mount(platform, '/registry?page=2')
        await screen.findByText('Заявок не найдено.')
        expect(screen.getByRole('button', {name: 'Назад'})).toBeEnabled()
        expect(screen.getByText('Страница 3')).toBeInTheDocument()
    })
    it('does not expose an identifier before the authorized row has loaded', async () => {
        list.mockResolvedValueOnce({
            ...empty,
            items: [item],
        })

        mount()

        await screen.findByRole('link', {
            name: 'Открыть карточку',
        })

        fireEvent.click(
            screen.getByText(`Номер ${item.requestId.slice(0, 8)}`),
        )

        const copyLabel = `Скопировать номер заявки ${item.requestId}`

        expect(
            screen.getByRole('button', {name: copyLabel}),
        ).toBeInTheDocument()

        expect(screen.getByText(item.requestId)).toBeInTheDocument()

        selectState('HELD')

        expect(
            screen.queryByRole('button', {name: copyLabel}),
        ).not.toBeInTheDocument()

        await screen.findByText('Заявок не найдено.')

        expect(
            screen.queryByRole('button', {name: copyLabel}),
        ).not.toBeInTheDocument()

        expect(
            screen.queryByText(item.requestId),
        ).not.toBeInTheDocument()
    })

})

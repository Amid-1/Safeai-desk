// frontend/src/features/models/ui/ModelControlPlaneViews.safety.test.tsx
// Проверка отображения Runtime, каталога, прав администратора и доказательств маршрутизации.

import {fireEvent, render, screen, within} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import type {ModelRouteDecision, RuntimeModelProbe} from '../../../entities/model/api/modelApi'
import {
    CatalogTable,
    PolicyCard,
    RouteDecisionEvidence,
    RuntimeCard,
} from './ModelControlPlaneViews'
import {
    createModelCatalogEntry,
    createOrganizationModelPolicy,
    createRuntimeModelStatus,
} from '../../../test/fixtures/modelControlPlaneTestFixtures'

const runtime = createRuntimeModelStatus()
const entry = createModelCatalogEntry()
const policy = createOrganizationModelPolicy()
const probe: RuntimeModelProbe = {
    provider: runtime.provider,
    model: runtime.model,
    status: 'AVAILABLE',
    checkedAt: '2026-10-21T06:00:00Z',
    latencyMs: 45,
    httpStatus: 200,
    message: 'Проверка пройдена',
}
const policyProps = {
    policy, catalog: [entry], effectiveCatalog: [entry], runtime,
    organizationId: policy.organizationId, organizationName: 'Компания',
    isSuperAdmin: false, organizationQuery: '', organizationResults: [],
    organizationSearchPending: false,
    organizationSearchStatus: 'idle' as const,
    organizationSearchError: '', onOrganizationQueryChange: vi.fn(),
    onOrganizationSearch: vi.fn(), onOrganizationSelect: vi.fn(), onEdit: vi.fn(),
}
const routeDecision: ModelRouteDecision = {
    id: '00000000-0000-0000-0000-000000000011',
    organizationId: policy.organizationId,
    userId: '00000000-0000-0000-0000-000000000012',
    chatId: '00000000-0000-0000-0000-000000000013',
    chatTurnId: '00000000-0000-0000-0000-000000000014',
    clientRequestId: '00000000-0000-0000-0000-000000000015',
    requestContentHash: 'a'.repeat(64),
    requestedModelKey: null,
    selectedCatalogEntryId: entry.id,
    selectedCatalogVersion: 1,
    selectedModelKey: entry.modelKey,
    selectedProvider: runtime.provider,
    selectedProviderModelId: runtime.model,
    policyId: null,
    policyVersion: null,
    requiredCapabilities: [],
    inputAccountingVersion: 'UTF8_STRUCTURAL_UNITS_V2',
    additionalInputUnitUpperBound: 512,
    estimatedInputTokens: 1024,
    estimatedOutputTokens: 256,
    estimatedMaxCostUsd: '0.012345678901',
    monthlyBudgetUsd: '100.000000000000',
    monthlySpentUsd: '0.000000000000',
    monthlyProjectedUsd: '0.012345678901',
    monthlyCostKnown: true,
    monthlyCostState: 'KNOWN',
    budgetEnforcement: 'HARD',
    budgetExceeded: false,
    pricingComplete: true,
    outcome: 'ALLOWED',
    reason: 'RUNTIME_ONLY_MATCH',
    decisionIntegrityVersion: 3,
    decisionSha256: 'b'.repeat(64),
    createdAt: '2026-10-21T06:00:00Z',
}

describe('RuntimeCard: достоверность сведений', () => {
    it('использует возможности Runtime, а не рекламные возможности каталога', () => {
        render(<RuntimeCard runtime={runtime}
            effectiveCatalog={[createModelCatalogEntry({capabilities: ['TOOLS','VISION','STRUCTURED_OUTPUT']})]}
            probe={null} probePending={false} canProbe={false} onProbe={vi.fn()} />)
        expect(screen.getByText('Поддерживаемые возможности')).toBeInTheDocument()
        const dt = screen.getByText('Поддерживаемые возможности').closest('dt')!
        expect(dt.nextElementSibling).toHaveTextContent('Текст')
        expect(dt.nextElementSibling).not.toHaveTextContent('Инструменты')
        expect(screen.queryByRole('button', {name: 'Проверить доступность'})).not.toBeInTheDocument()
    })

    it('показывает реально разрешённые Runtime-возможности', () => {
        render(<RuntimeCard runtime={{...runtime, toolsSupported: true, visionSupported: true, structuredOutputSupported: true}}
            effectiveCatalog={[entry]} probe={null} probePending={false} canProbe={false} onProbe={vi.fn()} />)
        expect(screen.getByText('Текст · Инструменты · Изображения · Структурированный ответ')).toBeInTheDocument()
    })

    it('не выдаёт отсутствие health probe за доступность и не делает сетевой запрос сам', () => {
        const onProbe = vi.fn()
        render(<RuntimeCard runtime={runtime} effectiveCatalog={[entry]} probe={null}
            probePending={false} canProbe onProbe={onProbe} />)
        expect(screen.getByText('Не выполнялась')).toBeInTheDocument()
        expect(onProbe).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('button', {name: 'Проверить доступность'}))
        expect(onProbe).toHaveBeenCalledTimes(1)
    })

    it('отключает кнопку на время проверки и сообщает HTTP/status отдельно', () => {
        const onProbe = vi.fn()
        render(<RuntimeCard runtime={runtime} effectiveCatalog={[entry]} probe={{...probe, status:'AUTH_ERROR', httpStatus:401}}
            probePending canProbe onProbe={onProbe} />)
        expect(screen.getByRole('button', {name: 'Проверяем...'})).toBeDisabled()
        expect(screen.getByRole('status')).toHaveTextContent('Ошибка авторизации')
        expect(screen.getByRole('status')).toHaveTextContent('HTTP 401')
        expect(onProbe).not.toHaveBeenCalled()
    })
})

describe('PolicyCard: права и смысл политики', () => {
    it('показывает инструмент выбора организации только суперадминистратору', () => {
        const onSearch = vi.fn()
        const onQueryChange = vi.fn()
        const onSelect = vi.fn()
        const organization = {id:'00000000-0000-0000-0000-000000000088', name:'Дочерняя компания', enabled:true, type:'TENANT' as const, protected:false, version:1}
        const {rerender} = render(<PolicyCard {...policyProps} />)
        expect(screen.queryByPlaceholderText('Начните вводить название или UUID')).not.toBeInTheDocument()
        rerender(<PolicyCard {...policyProps} isSuperAdmin
            organizationQuery="Доч" organizationResults={[organization]}
            onOrganizationQueryChange={onQueryChange} onOrganizationSearch={onSearch}
            onOrganizationSelect={onSelect} />)
        fireEvent.change(screen.getByPlaceholderText('Начните вводить название или UUID'), {target:{value:'Дочерняя'}})
        expect(onQueryChange).toHaveBeenCalledWith('Дочерняя')
        fireEvent.click(screen.getByRole('button', {name:'Найти'}))
        expect(onSearch).toHaveBeenCalledTimes(1)
        fireEvent.click(screen.getByRole('button', {name:/Дочерняя компания/}))
        expect(onSelect).toHaveBeenCalledWith(organization)
    })

    it('показывает ограничение по обучению только при реально действующей записи NOT_DECLARED', () => {
        const restricted = {...policy, configured:true, enabled:true, requireNoTraining:true}
        const {rerender} = render(<PolicyCard {...policyProps} policy={restricted} />)
        expect(screen.getByText('Требование запрета обучения не подтверждено')).toBeInTheDocument()
        rerender(<PolicyCard {...policyProps} policy={restricted}
            effectiveCatalog={[createModelCatalogEntry({trainingUseStatus:'NOT_USED'})]} />)
        expect(screen.queryByText('Требование запрета обучения не подтверждено')).not.toBeInTheDocument()
    })

    it('отображает unknown budget как отсутствие порога, не выдавая ноль', () => {
        render(<PolicyCard {...policyProps} />)
        expect(screen.getByText('Не задан')).toBeInTheDocument()
        expect(screen.getByText('Контроль бюджета выключен')).toBeInTheDocument()
        expect(screen.getByText('Без лимита')).toBeInTheDocument()
    })
})

describe('CatalogTable: последняя и действующая версии', () => {
    it.each(['ACTIVE','DEPRECATED','DISABLED','RETIRED'] as const)(
        'различает статус жизненного цикла %s', lifecycle => {
            render(<CatalogTable entries={[createModelCatalogEntry({lifecycle})]}
                effectiveEntries={[entry]} runtime={runtime} canEdit={false}
                onCreateVersion={vi.fn()} onOpenHistory={vi.fn()} />)
            const text = {ACTIVE:'Активна',DEPRECATED:'Устаревает',DISABLED:'Отключена',RETIRED:'Выведена'}[lifecycle]
            expect(screen.getByText(text)).toBeInTheDocument()
        },
    )

    it('не вычисляет действующую версию по часам браузера', () => {
        const latest = createModelCatalogEntry({id:'00000000-0000-0000-0000-000000000088',version:2,effectiveFrom:'2099-01-01T00:00:00Z'})
        render(<CatalogTable entries={[latest]} effectiveEntries={[entry]} runtime={runtime} canEdit
            onCreateVersion={vi.fn()} onOpenHistory={vi.fn()} />)
        expect(screen.getByText('Сейчас действует версия 1')).toBeInTheDocument()
        expect(screen.getByText('Последняя версия пока не действует')).toBeInTheDocument()
        expect(screen.getByRole('button', {name:'Новая версия'})).toBeInTheDocument()
    })

    it('read-only пользователь может открыть историю, но не создать новую версию', () => {
        const onHistory = vi.fn()
        const onCreate = vi.fn()
        render(<CatalogTable entries={[entry]} effectiveEntries={[entry]} runtime={runtime}
            canEdit={false} onOpenHistory={onHistory} onCreateVersion={onCreate} />)
        expect(screen.queryByRole('button', {name:'Новая версия'})).not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', {name:'История версий'}))
        expect(onHistory).toHaveBeenCalledWith(entry)
        expect(onCreate).not.toHaveBeenCalled()
    })

    it('показывает отсутствие действующей записи без ложной исполнимости', () => {
        render(<CatalogTable entries={[entry]} effectiveEntries={[]} runtime={runtime} canEdit={false}
            onCreateVersion={vi.fn()} onOpenHistory={vi.fn()} />)
        expect(screen.getByText('До даты вступления в силу модель не участвует в маршрутизации')).toBeInTheDocument()
        expect(screen.queryByText('Действующая версия физически исполнима текущим Runtime')).not.toBeInTheDocument()
    })
})

describe('RouteDecisionEvidence: точность доказательств', () => {
    it('показывает provenance версии v3, хэш и безопасные decimal-строки', () => {
        render(<RouteDecisionEvidence decision={routeDecision} />)
        expect(screen.getByText('Доказательства маршрутизации V48')).toBeInTheDocument()
        expect(screen.getByText('v3')).toBeInTheDocument()
        expect(screen.getByText('UTF8_STRUCTURAL_UNITS_V2')).toBeInTheDocument()
        expect(screen.getByText('512')).toBeInTheDocument()
        expect(screen.getByText(`SHA-256: ${'b'.repeat(64)}`)).toBeInTheDocument()
        const budget = screen.getByText('Месячный бюджет').closest('div')!
        expect(within(budget).getByText('Известна', {exact:false})).toBeInTheDocument()
    })

    it('не приписывает неизвестной стоимости нулевую цену', () => {
        render(<RouteDecisionEvidence decision={{
            ...routeDecision, estimatedMaxCostUsd:null, monthlyBudgetUsd:null,
            monthlyCostState:'UNKNOWN', monthlyCostKnown:false,
            budgetEnforcement:null, outcome:'DENIED', reason:'MONTHLY_BUDGET_UNVERIFIABLE',
            chatTurnId:null,
        }} />)
        expect(screen.getByText('Неизвестно')).toBeInTheDocument()
        expect(screen.getAllByText('Не применялся', {exact:false}).length).toBeGreaterThan(0)
        expect(screen.getByText('Неизвестна', {exact:false})).toBeInTheDocument()
        expect(screen.getByText(/Отклонено · Невозможно надёжно проверить месячный бюджет/)).toBeInTheDocument()
    })

    it('отображает исторические решения без придуманного provenance v3', () => {
        render(<RouteDecisionEvidence decision={{
            ...routeDecision, inputAccountingVersion:null, additionalInputUnitUpperBound:null,
            decisionIntegrityVersion:2,
        }} />)
        expect(screen.getByText('v2')).toBeInTheDocument()
        expect(screen.getByText('Историческое решение V1/V2')).toBeInTheDocument()
    })
})

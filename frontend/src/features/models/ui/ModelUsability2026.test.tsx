// frontend/src/features/models/ui/ModelUsability2026.test.tsx
// Проверка новых пользовательских формулировок, ролей и статусов поиска.
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ModelAvailabilitySummary } from './ModelAvailabilitySummary'
import { PolicyCard } from './ModelControlPlaneViews'
import {
    createRuntimeModelStatus,
    createOrganizationModelPolicy,
    createModelCatalogEntry,
} from '../../../test/fixtures/modelControlPlaneTestFixtures'

const runtime = createRuntimeModelStatus()
const policy = createOrganizationModelPolicy()
const entry = createModelCatalogEntry()

function policyProps() {
    return {
        policy,
        catalog: [entry],
        effectiveCatalog: [entry],
        runtime,
        organizationId: policy.organizationId,
        organizationName: 'SafeAI Platform',
        isSuperAdmin: true,
        organizationQuery: 'Ромашка',
        organizationResults: [],
        organizationSearchPending: false,
        organizationSearchStatus: 'empty' as const,
        organizationSearchError: '',
        onOrganizationQueryChange: vi.fn(),
        onOrganizationSearch: vi.fn(),
        onOrganizationSelect: vi.fn(),
        onEdit: vi.fn(),
    }
}

describe('Модели: понятный и безопасный интерфейс', () => {
    it('не выдаёт предварительную конфигурацию за разрешение на выполнение запроса', () => {
        const onOpenPolicy = vi.fn()
        render(<ModelAvailabilitySummary
            runtime={runtime}
            effectiveCatalog={[entry]}
            policy={{ ...policy, configured: true }}
            organizationName="SafeAI Platform"
            onOpenPolicy={onOpenPolicy}
        />)
        expect(screen.getByRole('heading', {name: 'Предварительная проверка совместимости'})).toBeInTheDocument()
        expect(screen.getByText('Требуется проверка запроса')).toBeVisible()
        expect(screen.getByText(/Окончательный доступ определяется сервером/)).toBeInTheDocument()
        expect(screen.getByText('Показать подробности').closest('details')).not.toHaveAttribute('open')
        // Серверная проверка доступна и при закрытых подробностях.
        expect(screen.getAllByRole('button')).toHaveLength(1)
        fireEvent.click(screen.getByRole('button', {name: 'Открыть проверку правил'}))
        expect(onOpenPolicy).toHaveBeenCalledTimes(1)
    })

    it('показывает понятное пустое состояние поиска без выбора другой организации', () => {
        render(<PolicyCard {...policyProps()} />)
        const field = screen.getByRole('combobox', {name:'Управляемая организация'})
        expect(field).toHaveValue('SafeAI Platform')
        fireEvent.focus(field)
        expect(screen.getByText(/Ничего не найдено/)).toBeInTheDocument()
        expect(screen.getByText('SafeAI Platform')).toBeInTheDocument()
    })

    it('представляет ошибку поиска отдельно от общих ошибок страницы', () => {
        render(<PolicyCard {...policyProps()}
            organizationSearchStatus="error"
            organizationSearchError="Справочник временно недоступен"
        />)
        expect(screen.getByRole('status')).toHaveTextContent('Справочник временно недоступен')
        expect(screen.queryByText(/Ничего не найдено/)).not.toBeInTheDocument()
    })

    it('не отображает поиск организации обычному ADMIN', () => {
        render(<PolicyCard {...policyProps()} isSuperAdmin={false} />)
        expect(screen.queryByRole('combobox', {name: 'Управляемая организация'}))
            .not.toBeInTheDocument()
    })

    it('копирует полный UUID выбранной организации', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined)
        vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
        try {
            render(<PolicyCard {...policyProps()} isSuperAdmin={false} />)
            fireEvent.click(screen.getByText('Технические сведения'))
            fireEvent.click(screen.getByRole('button', { name: 'Копировать UUID организации' }))
            expect(await screen.findByText('UUID скопирован')).toBeInTheDocument()
            expect(writeText).toHaveBeenCalledWith(policy.organizationId)
        } finally {
            vi.unstubAllGlobals()
        }
    })

    it('различает отсутствие правил и сохранённую отключённую версию', () => {
        const { rerender } = render(<PolicyCard {...policyProps()} />)
        expect(screen.getByText(/Сервер блокирует AI-запросы/)).toBeInTheDocument()
        rerender(<PolicyCard {...policyProps()} policy={{ ...policy, configured: true, version: 2, enabled: false }} />)
        expect(screen.queryByText(/Сервер блокирует AI-запросы/)).not.toBeInTheDocument()
        expect(screen.getByText(/дополнительные ограничения этой версии не применяются/)).toBeInTheDocument()
        expect(screen.getAllByRole('button', { name: 'Изменить правила' })).toHaveLength(1)
    })

    it('для модели по умолчанию показывает параметры действующей версии, а не будущей', () => {
        render(<PolicyCard {...policyProps()}
            policy={{ ...policy, configured: true, enabled: true, defaultModelKey: entry.modelKey }}
            catalog={[{ ...entry, providerModelId: 'future-model', version: 2 }]}
            effectiveCatalog={[entry]} />)
        expect(screen.getByText('mock / mock-safeai')).toBeInTheDocument()
        expect(screen.queryByText(/future-model/)).not.toBeInTheDocument()
    })

    it('различает автоматический выбор, закреплённую модель и заданные лимиты', () => {
        const {rerender} = render(<PolicyCard {...policyProps()} />)
        expect(screen.getByText('Автоматически')).toBeVisible()
        expect(screen.getByText('Текущая модель: mock-safeai · mock')).toBeVisible()
        const restricted = {...policy, configured: true, enabled: true, defaultModelKey: entry.modelKey,
            maxInputTokens: 1000, maxOutputTokens: null, monthlyBudgetUsd: '10', budgetEnforcement: 'HARD' as const,
            maxRequestCostUsd: '0.01', requireCompletePricing: true, requireNoTraining: true, requireZeroDataRetention: true}
        rerender(<PolicyCard {...policyProps()} policy={restricted} />)
        expect(screen.getByText(/Вход: 1\s000 · выход: без доп\. лимита/)).toBeVisible()
        expect(screen.getByText('Жёсткий контроль: блокировка превышения')).toBeVisible()
        expect(screen.getByText('Полные сведения о стоимости · Запрет использования данных для обучения · Нулевое хранение данных у провайдера')).toBeVisible()
        expect(screen.getByText('mock / mock-safeai')).toBeVisible()
        expect(screen.queryByText('Автоматически')).not.toBeInTheDocument()
    })

    it('не открывает редактор при заблокированном редактировании', () => {
        const onEdit = vi.fn()
        render(<PolicyCard {...policyProps()} editDisabled onEdit={onEdit} />)
        const shortcuts = screen.getAllByRole('button', {name: /^Настроить:/})
        expect(shortcuts).toHaveLength(6)
        for (const button of shortcuts) {
            expect(button).toBeDisabled()
            fireEvent.click(button)
        }
        expect(screen.getByRole('button', {name: 'Открыть проверку правил'})).toBeDisabled()
        expect(onEdit).not.toHaveBeenCalled()
    })
})

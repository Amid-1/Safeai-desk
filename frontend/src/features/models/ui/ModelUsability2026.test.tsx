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
            policy={policy}
            organizationName="SafeAI Platform"
            onOpenPolicy={onOpenPolicy}
        />)
        expect(screen.getByRole('heading', {name: 'Проверка доступности модели'})).toBeInTheDocument()
        expect(screen.getByText('Нужна проверка запроса')).toBeInTheDocument()
        expect(screen.getByText(/Окончательный доступ определяется сервером/)).toBeInTheDocument()
        // Три сводные карточки не должны быть ложными кнопками.
        expect(screen.getAllByRole('button')).toHaveLength(1)
        fireEvent.click(screen.getByRole('button', {name: 'Открыть проверку правил'}))
        expect(onOpenPolicy).toHaveBeenCalledTimes(1)
    })

    it('показывает понятное пустое состояние поиска без выбора другой организации', () => {
        render(<PolicyCard {...policyProps()} />)
        expect(screen.getByRole('searchbox', {name: 'Проверить другую организацию'}))
            .toHaveValue('Ромашка')
        expect(screen.getByRole('status')).toHaveTextContent('Ничего не найдено')
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
        expect(screen.queryByRole('searchbox', {name: 'Проверить другую организацию'}))
            .not.toBeInTheDocument()
    })
})

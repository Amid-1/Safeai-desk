// frontend/src/features/audit/ui/AdminAuditPageView.test.tsx
// ============================================================
// frontend/src/features/audit/ui/AdminAuditPageView.test.tsx
// Проверки представления, экспорта CSV, таблицы, состояний и действий.
// ============================================================
import { fireEvent, render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminAuditController } from '../controller/useAdminAuditController'
import { downloadAuditCsv } from './auditCsv'
import { AdminAuditPageView } from './AdminAuditPageView'

vi.mock('../../../shared/ui/ResizableScrollRegion', () => ({
    default: ({ upper, footer, children }: { upper: ReactNode; footer?: ReactNode; children: ReactNode }) => (
        <section data-testid="audit-scroll-region">
            <div data-testid="audit-upper">{upper}</div>
            <div data-testid="audit-viewport">{children}</div>
            <div data-testid="audit-footer">{footer}</div>
        </section>
    ),
}))
vi.mock('./AuditDetailsModal', () => ({
    default: ({ organizationFallbackName, onClose }: {
        organizationFallbackName?: string
        onClose: () => void
    }) => (
        <div role="dialog" aria-label="Подробности события">
            <span>{organizationFallbackName ?? 'Не указана'}</span>
            <button type="button" onClick={onClose}>Закрыть детали</button>
        </div>
    ),
}))
vi.mock('./auditCsv', async (importOriginal) => ({
    ...await importOriginal<typeof import('./auditCsv')>(),
    downloadAuditCsv: vi.fn(),
}))

const csvMock = vi.mocked(downloadAuditCsv)
const EVENT = {
    id: '33333333-3333-4333-8333-333333333333',
    targetOrganizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    targetOrganizationName: 'Демо',
    actorUserId: null, actorOrganizationId: null,
    actorEmail: 'admin@example.com', actorDisplayName: 'Администратор',
    eventType: 'USER_CREATED', details: { action: 'ok' },
    detailsTruncated: false, detailsInvalid: false,
    createdAt: '2026-10-20T08:00:00Z',
} satisfies AdminAuditController['events'][number]

function mount(overrides: Partial<AdminAuditController> = {}) {
    const controller: AdminAuditController = {
        superAdmin: false,
        selectedEvent: null,
        draftFilter: {
            eventType: '', actorUserId: '', actorEmail: '', dateFrom: '2026-10-01',
            dateTo: '2026-10-20', targetOrganizationId: '',
        },
        eventTypes: ['USER_CREATED'], organizations: [], actors: [],
        directoriesLoading: false, eventTypesError: '', organizationsError: '',
        actorsError: '', filterError: '', filtersDirty: false,
        effectiveLoading: false,
        events: [EVENT], loadError: '',
        pageResponse: {
            content: [EVENT], page: 1, size: 50, totalPages: 3, totalElements: 130,
        },
        selectedOrganizationFallback: undefined,
        showTable: true,
        searchActors: vi.fn().mockResolvedValue(undefined),
        searchOrganizations: vi.fn().mockResolvedValue(undefined),
        setDraftFilter: vi.fn(), setSelectedEvent: vi.fn(),
        applyDatePreset: vi.fn(), applyFilters: vi.fn(), resetFilters: vi.fn(),
        retryLoad: vi.fn(), changePage: vi.fn(),
        ...overrides,
    }
    render(<AdminAuditPageView controller={controller} />)
    return controller
}

beforeEach(() => vi.clearAllMocks())

describe('AdminAuditPageView — форма и рабочая область', () => {
    it('оставляет фильтры и экспорт в upper, таблицу в viewport и пагинацию в footer', () => {
        mount()
        expect(within(screen.getByTestId('audit-upper')).getByText('Аудит событий')).toBeInTheDocument()
        expect(within(screen.getByTestId('audit-upper')).getByRole('button', { name: 'Скачать CSV текущей страницы' })).toBeInTheDocument()
        expect(within(screen.getByTestId('audit-viewport')).getByRole('table')).toBeInTheDocument()
        expect(within(screen.getByTestId('audit-footer')).getByLabelText('Пагинация аудита')).toBeInTheDocument()
    })

    it('экспортирует только события текущей страницы, а не весь журнал', () => {
        const data = mount()
        fireEvent.click(screen.getByRole('button', { name: 'Скачать CSV текущей страницы' }))
        expect(csvMock).toHaveBeenCalledTimes(1)
        expect(csvMock).toHaveBeenCalledWith(data.events)
    })

    it('предупреждает о неприменённых изменениях, не подменяя экспорт', () => {
        mount({ filtersDirty: true })
        expect(screen.getByText(/Не примененные изменения фильтров в файл не входят/)).toBeInTheDocument()
    })

    it('отключает экспорт и перелистывание во время перехода', () => {
        mount({ effectiveLoading: true })
        expect(screen.getByRole('button', { name: 'Скачать CSV текущей страницы' })).toBeDisabled()
        expect(screen.getByRole('button', { name: 'Вперёд' })).toBeDisabled()
    })

    it('показывает загрузку без устаревшей таблицы', () => {
        mount({ events: [], showTable: false, effectiveLoading: true })
        expect(screen.getByText('Загрузка событий аудита...')).toBeInTheDocument()
        expect(screen.queryByRole('table')).not.toBeInTheDocument()
    })

    it('показывает ошибку и выполняет только callback повторной загрузки', () => {
        const data = mount({ events: [], showTable: false, loadError: 'Сервис недоступен' })
        expect(screen.getByText('Сервис недоступен')).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
        expect(data.retryLoad).toHaveBeenCalledOnce()
        expect(csvMock).not.toHaveBeenCalled()
    })

    it('показывает пустое состояние без таблицы и CSV', () => {
        mount({ events: [], showTable: false })
        expect(screen.getByText('Событий не найдено')).toBeInTheDocument()
        expect(screen.queryByRole('table')).not.toBeInTheDocument()
        expect(screen.queryByRole('button', { name: 'Скачать CSV текущей страницы' })).not.toBeInTheDocument()
    })

    it('использует серверную пагинацию вместо самостоятельной выборки в компоненте', () => {
        const data = mount()
        fireEvent.click(screen.getByRole('button', { name: 'Вперёд' }))
        expect(data.changePage).toHaveBeenCalledTimes(1)
        expect(data.changePage).toHaveBeenCalledWith(2)
    })

    it('передаёт открытие выбранной записи в контроллер', () => {
        const data = mount()
        fireEvent.click(screen.getByRole('button', { name: 'Подробнее' }))
        expect(data.setSelectedEvent).toHaveBeenCalledWith(EVENT)
    })

    it('открывает детали выбранного события и передаёт закрытие контроллеру', () => {
        const data = mount({ selectedEvent: EVENT, selectedOrganizationFallback: 'Контур Б' })
        const dialog = screen.getByRole('dialog', { name: 'Подробности события' })
        expect(within(dialog).getByText('Контур Б')).toBeInTheDocument()
        fireEvent.click(within(dialog).getByRole('button', { name: 'Закрыть детали' }))
        expect(data.setSelectedEvent).toHaveBeenCalledWith(null)
    })

    it('не отображает организационный фильтр пользователю без SUPER_ADMIN', () => {
        mount({ superAdmin: false })
        expect(screen.queryByLabelText(/Целевая организация/)).not.toBeInTheDocument()
    })
})

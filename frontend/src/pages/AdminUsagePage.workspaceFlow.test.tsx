// frontend/src/pages/AdminUsagePage.workspaceFlow.test.tsx
// Регрессии рабочего пространства: клавиатура, resize, переключение режимов и экспорт.
import { fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { downloadUsageCsv } from '../features/usage/ui/usageCsv'
import AdminUsagePage from './AdminUsagePage'

/**
 * Проверяется видимый пользователю переход между режимами страницы.
 * Геометрия ResizeObserver/JSDOM намеренно не подменяется фиктивными пикселями.
 * Имена именованных мок-экспортов проверяются по настоящим модулям.
 */
vi.mock('../shared/ui/PageErrorBoundary', () => ({
    default: ({ children }: { children: ReactNode }) => <>{children}</>,
}))

vi.mock('../features/usage/controller/useAdminUsageReport', () => {
    const entries = [
        ['useAdminUsageReport', () => ({
            tab: 'summary',
            rows: [{ id: 'visible' }],
            loading: false,
            error: '',
            page: 0,
            hasNext: false,
            hasPrevious: false,
            appliedDateFrom: '2026-09-21',
            appliedDateTo: '2026-10-20',
            appliedModel: '',
            scopeLabel: 'Все организации',
            filtersDirty: false,
        })],
    ] satisfies ReadonlyArray<
        readonly [keyof typeof import('../features/usage/controller/useAdminUsageReport'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../shared/ui/ResizableScrollRegion', () => ({
    default: ({ children, upper, footer, showLowerRequest, showUpperRequest }: {
        children: ReactNode
        upper: ReactNode
        footer: ReactNode
        showLowerRequest: number
        showUpperRequest: number
    }) => (
        <div
            data-testid="usage-workspace"
            data-expand-requests={showLowerRequest}
            data-restore-requests={showUpperRequest}
        >
            {upper}
            <div className="resizable-scroll-region__lower">
                <div className="resizable-scroll-region__handle"
                     role="separator" aria-label="Изменить размер области результатов">
                    <span aria-hidden="true" />
                </div>
                {children}
                {footer}
            </div>
        </div>
    ),
}))

vi.mock('../features/usage/ui/AdminUsageControls', () => {
    const entries = [
        ['AdminUsageControls', ({ onExpandResults, onRestoreFilters }: {
            onExpandResults: () => void
            onRestoreFilters: () => void
        }) => (
            <>
                <button type="button" onClick={onExpandResults}>
                    Развернуть результаты
                </button>
                <button type="button" onClick={onRestoreFilters}>
                    Показать фильтры
                </button>
            </>
        )],
    ] satisfies ReadonlyArray<
        readonly [keyof typeof import('../features/usage/ui/AdminUsageControls'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../features/usage/ui/UsageReportView', () => {
    const entries = [
        ['UsageReportFooter', () => <div>Итоговая строка</div>],
        ['UsageRows', () => (
            <table>
                <tbody>
                    <tr>
                        <td>Данные отчета</td>
                    </tr>
                </tbody>
            </table>
        )],
    ] satisfies ReadonlyArray<
        readonly [keyof typeof import('../features/usage/ui/UsageReportView'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../features/usage/ui/UsageKpis', () => {
    const entries = [
        ['UsageKpis', () => <div>Показатели</div>],
    ] satisfies ReadonlyArray<
        readonly [keyof typeof import('../features/usage/ui/UsageKpis'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../features/usage/ui/UsageBreakdownChart', () => {
    const entries = [
        ['UsageBreakdownChart', () => <div>Сравнение расхода токенов</div>],
    ] satisfies ReadonlyArray<readonly [keyof typeof import('../features/usage/ui/UsageBreakdownChart'), unknown]>
    return Object.fromEntries(entries)
})

vi.mock('../features/usage/ui/UsageDailyChart', () => {
    const entries = [
        ['UsageDailyChart', () => <div>График</div>],
    ] satisfies ReadonlyArray<
        readonly [keyof typeof import('../features/usage/ui/UsageDailyChart'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../features/usage/ui/usageCsv', () => {
    const entries = [
        ['downloadUsageCsv', vi.fn()],
    ] satisfies ReadonlyArray<
        readonly [keyof typeof import('../features/usage/ui/usageCsv'), unknown]
    >

    return Object.fromEntries(entries)
})

describe('Usage page does not clip short reports', () => {
    beforeEach(() => { vi.clearAllMocks() })
    it('keeps a real drag entry point in normal flow', () => {
        const { container } = render(<AdminUsagePage />)
        const page = container.querySelector('.usage-page')
        expect(page?.classList.contains('usage-page--flow')).toBe(true)
        fireEvent.pointerDown(screen.getByRole('separator', {
            name: 'Изменить размер области результатов',
        }), { button: 0 })
        expect(page?.classList.contains('usage-page--focused')).toBe(true)
    })

    it('starts in full flow, expands the workspace and restores filters', () => {
        const { container } = render(<AdminUsagePage />)
        const page = container.querySelector('.usage-page')
        const workspace = screen.getByTestId('usage-workspace')

        expect(page?.classList.contains('usage-page--flow')).toBe(true)
        expect(workspace.getAttribute('data-expand-requests')).toBe('0')
        expect(workspace.getAttribute('data-restore-requests')).toBe('0')
        expect(screen.getByText('Показатели')).toBeTruthy()
        expect(screen.getByText('Данные отчета')).toBeTruthy()
        expect(screen.getByText('Итоговая строка')).toBeTruthy()
        expect(screen.getByText('Сравнение расхода токенов')).toBeTruthy()

        fireEvent.click(screen.getByRole('button', {
            name: 'Развернуть результаты',
        }))

        expect(page?.classList.contains('usage-page--focused')).toBe(true)
        expect(workspace.getAttribute('data-expand-requests')).toBe('1')
        expect(screen.getByText('Данные отчета')).toBeTruthy()
        expect(screen.getByText('Итоговая строка')).toBeTruthy()
        expect(screen.getByText('Сравнение расхода токенов')).toBeTruthy()

        fireEvent.click(screen.getByRole('button', {
            name: 'Показать фильтры',
        }))

        expect(page?.classList.contains('usage-page--flow')).toBe(true)
        expect(workspace.getAttribute('data-restore-requests')).toBe('1')
        expect(screen.getByText('Показатели')).toBeTruthy()
        expect(screen.getByText('Данные отчета')).toBeTruthy()
        expect(screen.getByText('Итоговая строка')).toBeTruthy()
        expect(screen.getByText('Сравнение расхода токенов')).toBeTruthy()
    })

    it('экспортирует ровно отображаемый набор данных и выбранную вкладку', () => {
        render(<AdminUsagePage />)
        fireEvent.click(screen.getByRole('button', { name: 'Скачать CSV' }))
        expect(vi.mocked(downloadUsageCsv)).toHaveBeenCalledTimes(1)
        expect(vi.mocked(downloadUsageCsv)).toHaveBeenCalledWith(
            'summary', [{ id: 'visible' }],
        )
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('не скрывает таблицу при ошибке формирования CSV и позволяет повторить', () => {
        vi.mocked(downloadUsageCsv).mockImplementationOnce(() => {
            throw new Error('Ошибка записи CSV')
        })
        render(<AdminUsagePage />)
        fireEvent.click(screen.getByRole('button', { name: 'Скачать CSV' }))
        expect(screen.getByRole('alert')).toHaveTextContent(
            'Не удалось создать CSV. Попробуйте повторно.',
        )
        expect(screen.getByText('Данные отчета')).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: 'Скачать CSV' }))
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
        expect(vi.mocked(downloadUsageCsv)).toHaveBeenCalledTimes(2)
    })

    it.each(['ArrowDown', 'ArrowUp', 'Home', 'End'])(
        'активирует управляемую область с клавиатуры: %s', (key) => {
            const { container } = render(<AdminUsagePage />)
            fireEvent.keyDown(screen.getByRole('separator', {
                name: 'Изменить размер области результатов',
            }), { key })
            expect(container.querySelector('.usage-page'))
                .toHaveClass('usage-page--focused')
        },
    )

    it('не включает режим разделения правой кнопкой и по посторонним клавишам', () => {
        const { container } = render(<AdminUsagePage />)
        const handle = screen.getByRole('separator', {
            name: 'Изменить размер области результатов',
        })
        fireEvent.pointerDown(handle, { button: 2 })
        fireEvent.keyDown(handle, { key: 'Enter' })
        expect(container.querySelector('.usage-page')).toHaveClass('usage-page--flow')
    })
})

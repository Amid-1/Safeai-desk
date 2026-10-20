import { fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import AdminUsagePage from './AdminUsagePage'

/**
 * Проверяется видимый пользователю переход между режимами страницы.
 * Геометрия ResizeObserver/JSDOM намеренно не подменяется фиктивными пикселями.
 * Имена именованных мок-экспортов проверяются по настоящим модулям.
 */
vi.mock('../components/PageErrorBoundary', () => ({
    default: ({ children }: { children: ReactNode }) => <>{children}</>,
}))

vi.mock('./useAdminUsageReport', () => {
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
        readonly [keyof typeof import('./useAdminUsageReport'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../components/ResizableScrollRegion', () => ({
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
            {children}
            {footer}
        </div>
    ),
}))

vi.mock('../components/admin/usage/AdminUsageControls', () => {
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
        readonly [keyof typeof import('../components/admin/usage/AdminUsageControls'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../components/admin/usage/UsageReportView', () => {
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
        readonly [keyof typeof import('../components/admin/usage/UsageReportView'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../components/admin/usage/UsageKpis', () => {
    const entries = [
        ['UsageKpis', () => <div>Показатели</div>],
    ] satisfies ReadonlyArray<
        readonly [keyof typeof import('../components/admin/usage/UsageKpis'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../components/admin/usage/UsageDailyChart', () => {
    const entries = [
        ['UsageDailyChart', () => <div>График</div>],
    ] satisfies ReadonlyArray<
        readonly [keyof typeof import('../components/admin/usage/UsageDailyChart'), unknown]
    >

    return Object.fromEntries(entries)
})

vi.mock('../components/admin/usage/usageCsv', () => {
    const entries = [
        ['downloadUsageCsv', vi.fn()],
    ] satisfies ReadonlyArray<
        readonly [keyof typeof import('../components/admin/usage/usageCsv'), unknown]
    >

    return Object.fromEntries(entries)
})

describe('Usage page does not clip short reports', () => {
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

        fireEvent.click(screen.getByRole('button', {
            name: 'Развернуть результаты',
        }))

        expect(page?.classList.contains('usage-page--focused')).toBe(true)
        expect(workspace.getAttribute('data-expand-requests')).toBe('1')
        expect(screen.getByText('Данные отчета')).toBeTruthy()
        expect(screen.getByText('Итоговая строка')).toBeTruthy()

        fireEvent.click(screen.getByRole('button', {
            name: 'Показать фильтры',
        }))

        expect(page?.classList.contains('usage-page--flow')).toBe(true)
        expect(workspace.getAttribute('data-restore-requests')).toBe('1')
        expect(screen.getByText('Показатели')).toBeTruthy()
        expect(screen.getByText('Данные отчета')).toBeTruthy()
        expect(screen.getByText('Итоговая строка')).toBeTruthy()
    })
})

import { EmptyState } from '../../StateBlock'
import type {
    UsageAmounts,
    UsageCoverage,
    UsageDailySummary,
    UsageModelSummary,
    UsageSummary,
    UsageUserSummary,
} from '../../../api/usageApi'
import type { UsageReportTab } from '../../../pages/adminUsagePageSupport'
import {
    countPart,
    formatCount,
    formatIsoDate,
    trimDecimal,
} from '../../../pages/adminUsagePageSupport'

type PagedRows =
    | UsageSummary[]
    | UsageUserSummary[]

export type UsageRowsType =
    | PagedRows
    | UsageModelSummary[]
    | UsageDailySummary[]

export function UsageReportFooter({
    tab,
    rowsCount,
    page,
    hasPrevious,
    hasNext,
    loading,
    onPageChange,
}: {
    tab: UsageReportTab
    rowsCount: number
    page: number
    hasPrevious: boolean
    hasNext: boolean
    loading: boolean
    onPageChange:
        (page: number) => void
}) {
    const paged =
        tab === 'summary'
        || tab === 'users'

    const summaryLabel =
        tab === 'models'
            ? 'Моделей'
            : tab === 'daily'
                ? 'Дней'
                : 'Записей'

    const allShownTitle =
        tab === 'models'
            ? 'Все модели показаны'
            : tab === 'daily'
                ? 'Все дни показаны'
                : 'Все записи показаны'

    if (
        !paged
        || (
            !hasPrevious
            && !hasNext
        )
    ) {
        return (
            <div className="pagination pagination--single">
                <div className="pagination__summary">
                    <strong>
                        {
                            allShownTitle
                        }
                    </strong>

                    <span>
                        {
                            summaryLabel
                        }
                        :
                        {' '}
                        {
                            rowsCount
                        }
                    </span>
                </div>
            </div>
        )
    }

    return (
        <div
            className="pagination"
            aria-label="Пагинация отчёта использования"
        >
            <button
                type="button"
                className="secondary-button"
                disabled={
                    loading
                    || !hasPrevious
                }
                onClick={() =>
                    onPageChange(
                        page - 1,
                    )
                }
            >
                Назад
            </button>

            <div className="pagination__summary">
                <strong>
                    Страница
                    {' '}
                    {
                        page + 1
                    }
                </strong>

                <span>
                    На странице:
                    {' '}
                    {
                        rowsCount
                    }
                </span>
            </div>

            <button
                type="button"
                className="secondary-button"
                disabled={
                    loading
                    || !hasNext
                }
                onClick={() =>
                    onPageChange(
                        page + 1,
                    )
                }
            >
                Вперёд
            </button>
        </div>
    )
}

export function UsageRows({
    tab,
    rows,
}: {
    tab: UsageReportTab
    rows: UsageRowsType
}) {
    if (
        rows.length === 0
    ) {
        return (
            <EmptyState
                title="За этот период использования нет"
                message="Данные появятся после завершённых обращений к AI. Выберите другой период или покажите последние 30 дней."
            />
        )
    }

    if (
        tab === 'summary'
    ) {
        return (
            <table className="admin-table usage-table">
                <thead>
                    <tr>
                        <th>
                            Пользователь
                        </th>

                        <th>
                            Модель
                        </th>

                        <th>
                            Вход
                        </th>

                        <th>
                            Выход
                        </th>

                        <th>
                            Всего
                        </th>

                        <th>
                            Известная стоимость
                        </th>

                        <th>
                            Качество данных
                        </th>
                    </tr>
                </thead>

                <tbody>
                    {
                        (rows as UsageSummary[]).map(
                            (
                                row,
                            ) => (
                                <UsageSummaryRow
                                    key={`${row.userId}:${row.model}`}
                                    row={
                                        row
                                    }
                                    showUser
                                    showModel
                                />
                            ),
                        )
                    }
                </tbody>
            </table>
        )
    }

    if (
        tab === 'users'
    ) {
        return (
            <table className="admin-table usage-table">
                <thead>
                    <tr>
                        <th>
                            Пользователь
                        </th>

                        <th>
                            Вход
                        </th>

                        <th>
                            Выход
                        </th>

                        <th>
                            Всего
                        </th>

                        <th>
                            Известная стоимость
                        </th>

                        <th>
                            Качество данных
                        </th>
                    </tr>
                </thead>

                <tbody>
                    {
                        (rows as UsageUserSummary[]).map(
                            (
                                row,
                            ) => (
                                <UsageSummaryRow
                                    key={
                                        row.userId
                                    }
                                    row={
                                        row
                                    }
                                    showUser
                                />
                            ),
                        )
                    }
                </tbody>
            </table>
        )
    }

    if (
        tab === 'models'
    ) {
        return (
            <table className="admin-table usage-table">
                <thead>
                    <tr>
                        <th>
                            Модель
                        </th>

                        <th>
                            Вход
                        </th>

                        <th>
                            Выход
                        </th>

                        <th>
                            Всего
                        </th>

                        <th>
                            Известная стоимость
                        </th>

                        <th>
                            Качество данных
                        </th>
                    </tr>
                </thead>

                <tbody>
                    {
                        (rows as UsageModelSummary[]).map(
                            (
                                row,
                            ) => (
                                <UsageSummaryRow
                                    key={
                                        row.model
                                    }
                                    row={
                                        row
                                    }
                                    showModel
                                />
                            ),
                        )
                    }
                </tbody>
            </table>
        )
    }

    return (
        <table className="admin-table usage-table">
            <thead>
                <tr>
                    <th>
                        Дата
                    </th>

                    <th>
                        Вход
                    </th>

                    <th>
                        Выход
                    </th>

                    <th>
                        Всего
                    </th>

                    <th>
                        Известная стоимость
                    </th>

                    <th>
                        Качество данных
                    </th>
                </tr>
            </thead>

            <tbody>
                {
                    (rows as UsageDailySummary[]).map(
                        (
                            row,
                        ) => (
                            <tr
                                key={
                                    row.usageDate
                                }
                            >
                                <td>
                                    {
                                        formatIsoDate(
                                            row.usageDate,
                                        )
                                    }
                                </td>

                                <UsageAmountCells
                                    row={
                                        row
                                    }
                                />
                            </tr>
                        ),
                    )
                }
            </tbody>
        </table>
    )
}

function UsageSummaryRow({
    row,
    showUser = false,
    showModel = false,
}: {
    row:
        UsageAmounts
        & {
            userEmail?: string
            model?: string
        }
    showUser?: boolean
    showModel?: boolean
}) {
    return (
        <tr>
            {showUser
                && (
                    <td>
                        {
                            row.userEmail
                            ?? '—'
                        }
                    </td>
                )}

            {showModel
                && (
                    <td>
                        {
                            row.model
                            ?? '—'
                        }
                    </td>
                )}

            <UsageAmountCells
                row={
                    row
                }
            />
        </tr>
    )
}

function UsageAmountCells({
    row,
}: {
    row: UsageAmounts
}) {
    return (
        <>
            <td>
                {
                    formatCount(
                        row.inputTokens,
                    )
                }
            </td>

            <td>
                {
                    formatCount(
                        row.outputTokens,
                    )
                }
            </td>

            <td>
                {
                    formatCount(
                        row.totalTokens,
                    )
                }

                {row.partialTotalTokens !== '0'
                    && (
                        <div className="muted">
                            + известно из partial:
                            {' '}
                            {
                                formatCount(
                                    row.partialTotalTokens,
                                )
                            }
                        </div>
                    )}
            </td>

            <td>
                {
                    row.costUsd === null
                        ? '—'
                        : `${trimDecimal(row.costUsd)} ${row.currency}`
                }

                {row.coverage.pricingComplete === false
                    && (
                        <div className="muted">
                            Это только известная часть стоимости.
                        </div>
                    )}
            </td>

            <td>
                <CoverageView
                    coverage={
                        row.coverage
                    }
                />
            </td>
        </>
    )
}

function CoverageView({
    coverage,
}: {
    coverage: UsageCoverage
}) {
    const usageText =
        coverage.usageComplete === true
            ? 'Токены учтены полностью'
            : coverage.usageComplete === false
                ? 'Токены учтены частично'
                : 'Полнота токенов неизвестна'

    const pricingText =
        coverage.pricingComplete === true
            ? 'Стоимость рассчитана'
            : coverage.pricingComplete === false
                ? 'Стоимость рассчитана частично'
                : 'Полнота стоимости неизвестна'

    const details = [
        countPart(
            'частичных ответов',
            coverage.partialUsageMessages,
        ),

        countPart(
            'без данных',
            coverage.missingUsageMessages,
        ),

        countPart(
            'без цены',
            coverage.unpricedMessages,
        ),

        countPart(
            'ошибок расчёта',
            coverage.pricingFailedMessages,
        ),
    ].filter(
        Boolean,
    )

    const quality =
        coverage.usageComplete === true
        && coverage.pricingComplete === true
            ? 'complete'
            : (
                coverage.usageComplete === false
                || coverage.pricingComplete === false
            )
                ? 'partial'
                : 'unknown'

    return (
        <div
            className={`usage-coverage usage-coverage--${quality}`}
        >
            <span
                className="usage-coverage__indicator"
                aria-hidden="true"
            />

            <div>
                <div>
                    {
                        usageText
                    }
                </div>

                <div>
                    {
                        pricingText
                    }
                </div>

                {details.length > 0
                    && (
                        <small className="muted">
                            {
                                details.join(
                                    ', ',
                                )
                            }
                        </small>
                    )}
            </div>
        </div>
    )
}

export function TabButton({
    active,
    onClick,
    children,
}: {
    active: boolean
    onClick: () => void
    children: string
}) {
    return (
        <button
            type="button"
            role="tab"
            aria-selected={
                active
            }
            className={
                active
                    ? 'filter-button active'
                    : 'filter-button'
            }
            onClick={
                onClick
            }
        >
            {
                children
            }
        </button>
    )
}


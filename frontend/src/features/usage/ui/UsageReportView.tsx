// frontend/src/features/usage/ui/UsageReportView.tsx
import { EmptyState } from '../../../shared/ui/StateBlock'
import type {
    UsageAmounts,
    UsageCoverage,
    UsageDailySummary,
    UsageModelSummary,
    UsageSummary,
    UsageUserSummary,
    UsageOrganizationSummary,
} from '../api/usageApi'
import type { UsageReportTab } from '../lib/adminUsagePageSupport'
import {
    countPart,
    formatCount,
    formatIsoDate,
    trimDecimal,
} from '../lib/adminUsagePageSupport'

type PagedRows =
    | UsageSummary[]
    | UsageUserSummary[]

export type UsageRowsType =
    | PagedRows
    | UsageModelSummary[]
    | UsageDailySummary[]
    | UsageOrganizationSummary[]

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
        || tab === 'users' || tab === 'organizations'

    const summaryLabel =
        tab === 'models'
            ? 'Моделей'
            : tab === 'daily'
                ? 'Дней'
                : tab === 'organizations' ? 'Организаций' : 'Записей'

    const allShownTitle =
        tab === 'models'
            ? 'Все модели показаны'
            : tab === 'daily'
                ? 'Все дни с записями'
                : tab === 'organizations' ? 'Все организации показаны' : 'Все записи показаны'

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
    onInspectModel,
    onInspectUser,
    appliedModel,
    appliedUserId,
    onInspectDay,
    relatedLinks = false,
    organizationId = '',
    drilldownDisabled = false,
}: {
    tab: UsageReportTab
    rows: UsageRowsType
    onInspectModel?: (model: string) => void
    onInspectUser?: (userId: string, email: string) => void
    appliedModel?: string
    appliedUserId?: string
    onInspectDay?: (date: string) => void
    relatedLinks?: boolean
    organizationId?: string
    drilldownDisabled?: boolean
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
                        <th scope="col">
                            Пользователь
                        </th>

                        <th scope="col">
                            Модель
                        </th>

                        <th scope="col">
                            Входные токены
                        </th>

                        <th scope="col">
                            Выходные токены
                        </th>

                        <th scope="col">
                            Всего подтверждено
                        </th>

                        <th scope="col">
                            Известная стоимость
                        </th>

                        <th scope="col">
                            Полнота учёта
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
                                    relatedLinks={relatedLinks} organizationId={organizationId}
                                    onInspectModel={row.model === appliedModel ? undefined : onInspectModel}
                                    onInspectUser={row.userId === appliedUserId ? undefined : onInspectUser}
                                    drilldownDisabled={drilldownDisabled}
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
                        <th scope="col">
                            Пользователь
                        </th>

                        <th scope="col">
                            Входные токены
                        </th>

                        <th scope="col">
                            Выходные токены
                        </th>

                        <th scope="col">
                            Всего подтверждено
                        </th>

                        <th scope="col">
                            Известная стоимость
                        </th>

                        <th scope="col">
                            Полнота учёта
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
                                    relatedLinks={relatedLinks} organizationId={organizationId}
                                    onInspectUser={onInspectUser}
                                    drilldownDisabled={drilldownDisabled}
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
                        <th scope="col">
                            Модель
                        </th>

                        <th scope="col">
                            Входные токены
                        </th>

                        <th scope="col">
                            Выходные токены
                        </th>

                        <th scope="col">
                            Всего подтверждено
                        </th>

                        <th scope="col">
                            Известная стоимость
                        </th>

                        <th scope="col">
                            Полнота учёта
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
                                    relatedLinks={relatedLinks} organizationId={organizationId}
                                    onInspectModel={onInspectModel}
                                    drilldownDisabled={drilldownDisabled}
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
                    <th scope="col">
                        Дата
                    </th>

                    <th scope="col">
                        Входные токены
                    </th>

                    <th scope="col">
                        Выходные токены
                    </th>

                    <th scope="col">
                        Всего подтверждено
                    </th>

                    <th scope="col">
                        Известная стоимость
                    </th>

                    <th scope="col">
                        Полнота учёта
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
                                    {onInspectDay ? (
                                        <button type="button" className="usage-drilldown"
                                            disabled={drilldownDisabled}
                                            title="Открыть детализацию за этот день UTC"
                                            onClick={() => onInspectDay(row.usageDate)}>
                                            {formatIsoDate(row.usageDate)} →
                                        </button>
                                    ) : formatIsoDate(row.usageDate)}
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
    onInspectModel,
    onInspectUser,
    relatedLinks = false,
    organizationId = '',
    drilldownDisabled = false,
}: {
    row:
        UsageAmounts
        & {
            userEmail?: string
            userId?: string
            model?: string
        }
    showUser?: boolean
    showModel?: boolean
    onInspectModel?: (model: string) => void
    onInspectUser?: (userId: string, email: string) => void
    relatedLinks?: boolean
    organizationId?: string
    drilldownDisabled?: boolean
}) {
    return (
        <tr>
            {showUser
                && (
                    <td>
                        {onInspectUser && row.userId ? <button type="button" className="usage-drilldown"
                            disabled={drilldownDisabled} title="Открыть детализацию по пользователю"
                            onClick={() => onInspectUser(row.userId!, row.userEmail ?? row.userId!)}>
                            {row.userEmail ?? row.userId} →
                        </button> : <span className="usage-user-label">
                            {row.userEmail ?? '—'}
                        </span>}
                        {relatedLinks && row.userId && <div><a className="admin-related-link usage-related-action"
                            href={`/admin/users?userId=${encodeURIComponent(row.userId)}`}>Карточка пользователя</a></div>}
                    </td>
                )}

            {showModel
                && (
                    <td>
                        {onInspectModel && row.model ? (
                            <button type="button" className="usage-drilldown"
                                disabled={drilldownDisabled}
                                title="Открыть детализацию по этой модели"
                                onClick={() => onInspectModel(row.model!)}>
                                {row.model} →
                            </button>
                        ) : row.model ?? '—'}
                        {relatedLinks && row.model && <div><a className="admin-related-link usage-related-action"
                            href={`/admin/models?section=catalog&usageModel=${encodeURIComponent(row.model)}${organizationId ? '&organizationId=' + encodeURIComponent(organizationId) : ''}`}>Карточка модели</a></div>}
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
                            Отдельно из неполных ответов:
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
                            Не все цены известны. Ноль не означает бесплатно.
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
                ? 'Не все цены известны'
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


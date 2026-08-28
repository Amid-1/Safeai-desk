// ============================================================
// frontend/src/components/admin/audit/AuditPagination.tsx
// ============================================================

type AuditPaginationProps = {
    page: number
    totalPages: number
    totalElements: number
    loading: boolean

    onPageChange:
        (page: number) => void
}

function AuditPagination({
    page,
    totalPages,
    totalElements,
    loading,
    onPageChange,
}: AuditPaginationProps) {
    const safeTotalPages =
        Math.max(
            totalPages,
            1,
        )

    return (
        <div
            className="pagination"
            aria-label={
                'Пагинация аудита'
            }
        >
            <button
                type="button"
                className="secondary-button"
                disabled={
                    loading
                    || page <= 0
                }
                onClick={() =>
                    onPageChange(
                        Math.max(
                            0,
                            page - 1,
                        ),
                    )
                }
            >
                Назад
            </button>

            <div
                className={
                    'pagination__summary'
                }
            >
                <strong>
                    Страница
                    {' '}
                    {page + 1}
                    {' '}
                    из
                    {' '}
                    {safeTotalPages}
                </strong>

                <span>
                    Всего событий:
                    {' '}
                    {totalElements}
                </span>
            </div>

            <button
                type="button"
                className="secondary-button"
                disabled={
                    loading
                    || totalPages === 0
                    || page + 1
                    >= totalPages
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

export default AuditPagination
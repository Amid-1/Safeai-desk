// ============================================================
// frontend/src/components/admin/audit/AuditPagination.tsx
// ============================================================
type AuditPaginationProps = {
    page: number
    totalPages: number
    totalElements: number
    loading: boolean
    onPageChange: (page: number) => void
}

function AuditPagination({
    page,
    totalPages,
    totalElements,
    loading,
    onPageChange,
}: AuditPaginationProps) {
    return (
        <nav
            className="pagination"
            aria-label={
                'Пагинация событий аудита'
            }
        >
            <button
                type="button"
                className="secondary-button"
                disabled={
                    page === 0 || loading
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

            <span>
                Страница
                {' '}
                {page + 1}
                {' '}
                из
                {' '}
                {Math.max(totalPages, 1)}
                .
                {' '}
                Всего событий:
                {' '}
                {totalElements}
            </span>

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
                    onPageChange(page + 1)
                }
            >
                Вперёд
            </button>
        </nav>
    )
}

export default AuditPagination

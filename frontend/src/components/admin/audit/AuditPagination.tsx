// ============================================================
// frontend/src/components/admin/audit/AuditPagination.tsx
// ============================================================
type AuditPaginationProps = {
    page: number
    totalPages: number
    loading: boolean
    onPageChange: (page: number) => void
}

function AuditPagination({
                             page,
                             totalPages,
                             loading,
                             onPageChange,
                         }: AuditPaginationProps) {
    return (
        <div className="pagination">
            <button
                type="button"
                className="secondary-button"
                disabled={page === 0 || loading}
                onClick={() =>
                    onPageChange(
                        Math.max(0, page - 1),
                    )
                }
            >
                Назад
            </button>

            <span>
                Страница {page + 1} из{' '}
                {Math.max(totalPages, 1)}
            </span>

            <button
                type="button"
                className="secondary-button"
                disabled={
                    loading ||
                    totalPages === 0 ||
                    page + 1 >= totalPages
                }
                onClick={() =>
                    onPageChange(page + 1)
                }
            >
                Вперёд
            </button>
        </div>
    )
}

export default AuditPagination

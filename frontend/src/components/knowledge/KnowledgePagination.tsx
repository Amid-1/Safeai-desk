type KnowledgePaginationProps = {
    page: number
    totalPages: number
    totalElements: number
    disabled?: boolean
    ariaLabel?: string
    singlePageMessage?: string
    onPageChange:
        (page: number) => void
}

function KnowledgePagination({
    page,
    totalPages,
    totalElements,
    disabled = false,
    ariaLabel = 'Пагинация баз знаний',
    singlePageMessage = 'Все базы показаны',
    onPageChange,
}: KnowledgePaginationProps) {
    if (totalElements === 0) {
        return null
    }

    if (totalPages <= 1) {
        return (
            <nav
                className="pagination pagination--single"
                aria-label={ariaLabel}
            >
                <div className="pagination__summary">
                    <strong>{singlePageMessage}</strong>
                    <span>Всего: {totalElements}</span>
                </div>
            </nav>
        )
    }

    return (
        <nav
            className="pagination"
            aria-label={ariaLabel}
        >
            <button
                type="button"
                className="secondary-button"
                disabled={
                    disabled
                    || page === 0
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
                Всего:
                {' '}
                {totalElements}
            </span>

            <button
                type="button"
                className="secondary-button"
                disabled={
                    disabled
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
        </nav>
    )
}

export default KnowledgePagination

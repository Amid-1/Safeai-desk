// frontend/src/features/models/ui/CatalogEmptyState.tsx
// ============================================================
type CatalogEmptyStateProps = {
    isSuperAdmin: boolean
    mutationPending: boolean
    onImportRuntime: () => void
    onCreateModel: () => void
}

function CatalogEmptyState({
    isSuperAdmin,
    mutationPending,
    onImportRuntime,
    onCreateModel,
}: CatalogEmptyStateProps) {
    return (
        <section
            className="models-empty-catalog"
            role="status"
            aria-live="polite"
        >
            <div
                className="models-empty-catalog__badge"
                aria-hidden="true"
            >
                <svg
                    viewBox="0 0 32 32"
                    focusable="false"
                >
                    <circle cx="7" cy="9" r="2" />
                    <circle cx="7" cy="16" r="2" />
                    <circle cx="7" cy="23" r="2" />
                    <path d="M12 9H25" />
                    <path d="M12 16H25" />
                    <path d="M12 23H25" />
                </svg>
            </div>

            <div className="models-empty-catalog__content">
                <span className="models-empty-catalog__eyebrow">
                    Каталог моделей
                </span>

                <h2>Каталог моделей пока пуст</h2>

                <p>
                    {isSuperAdmin
                        ? 'Добавьте подключённую модель в каталог или создайте первую неизменяемую версию модели вручную. Сам каталог не подключает провайдера к серверу.'
                        : 'Каталог ещё не настроен администратором. После заполнения здесь появятся последняя и действующая версии, лимиты, стоимость и параметры управления.'}
                </p>

                <div className="models-empty-catalog__info">
                    <div>
                        <strong>Что будет показано здесь</strong>
                        <small>
                            Последняя и действующая версии моделей, статус каталога,
                            лимиты, возможности, стоимость и дата вступления
                            версии в силу.
                        </small>
                    </div>
                    <div>
                        <strong>Как это связано с подключением</strong>
                        <small>
                            Каталог описывает сохранённую версию параметров модели.
                            Физический провайдер и модель настраиваются отдельно
                            на сервере.
                        </small>
                    </div>
                </div>

                {isSuperAdmin && (
                    <div className="models-empty-catalog__actions">
                        <button
                            type="button"
                            disabled={mutationPending}
                            onClick={onImportRuntime}
                        >
                            Добавить подключённую модель в каталог
                        </button>

                        <button
                            type="button"
                            className="btn-primary"
                            disabled={mutationPending}
                            onClick={onCreateModel}
                        >
                            Создать модель
                        </button>
                    </div>
                )}
            </div>
        </section>
    )
}


export default CatalogEmptyState

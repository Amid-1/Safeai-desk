// frontend/src/features/models/ui/catalog/ModelsCatalogWorkspace.tsx
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { CatalogTable } from '../ModelControlPlaneViews'
import CatalogEmptyState from '../CatalogEmptyState'

export function ModelsCatalogWorkspace({ controller }: { controller: AdminModelsController }) {
    const { catalog, effectiveCatalog, runtime, isSuperAdmin, mutationPending,
        handleImportRuntime, setCatalogModal, setHistoryModalEntry,
        catalogPermissionNotice, setCatalogPermissionNotice, importError } = controller
    if (!runtime) return null
    return (
        <section className="models-section-stack" aria-labelledby="models-catalog-title">
            <header className="models-section-heading models-section-heading--actions">
                <div>
                    <span className="models-eyebrow">ВЕРСИИ И ХАРАКТЕРИСТИКИ</span>
                    <h2 id="models-catalog-title">Каталог моделей</h2>
                    <p>Последняя сохранённая и действующая версии могут различаться. История доступна для каждой модели.</p>
                </div>
                {catalog.length > 0 && <div className="models-section-actions">
                    {isSuperAdmin && <button type="button" className="secondary-button" disabled={mutationPending}
                        onClick={() => { void handleImportRuntime() }}>Добавить подключённую модель в каталог</button>}
                    <button type="button" className={isSuperAdmin ? 'btn-primary' : 'secondary-button'} disabled={mutationPending}
                        onClick={() => {
                            if (!isSuperAdmin) { setCatalogPermissionNotice(true); return }
                            setCatalogModal({ base: null })
                        }}>Создать модель</button>
                </div>}
            </header>
            {importError && <div className="models-mutation-error" role="alert">
                <strong>Не удалось добавить подключённую модель в каталог</strong>
                <p>{importError}</p>
                <p>Если версия уже создана вручную, откройте её историю и создайте новую версию. Существующие данные не изменены.</p>
            </div>}
            {!isSuperAdmin && <p className="models-catalog-permission-hint" role="note">
                <strong>Каталог изменяет SUPER_ADMIN.</strong> Администратор организации может просматривать версии и изменять правила своей организации.
            </p>}
            {catalogPermissionNotice && !isSuperAdmin && <p role="status" className="models-notice">
                Создание и изменение версий каталога доступно только SUPER_ADMIN.
                Администратор организации может менять правила своей организации.
            </p>}
            <div className="models-catalog-table-region" role="region" aria-label="Таблица каталога моделей" tabIndex={0}>
                {catalog.length === 0 ? <CatalogEmptyState isSuperAdmin={isSuperAdmin} mutationPending={mutationPending}
                    onImportRuntime={() => { void handleImportRuntime() }} onCreateModel={() => setCatalogModal({ base: null })} />
                    : <CatalogTable entries={catalog} effectiveEntries={effectiveCatalog} runtime={runtime}
                        canEdit={isSuperAdmin}
                        onCreateVersion={(entry) => setCatalogModal({ base: entry })}
                        onOpenHistory={setHistoryModalEntry} />}
            </div>
            <div className="models-catalog-summary" aria-label="Сводка каталога">
                Моделей: {catalog.length} · Действующих версий: {effectiveCatalog.length}
            </div>
        </section>
    )
}

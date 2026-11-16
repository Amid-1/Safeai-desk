// frontend/src/features/models/ui/catalog/ModelsCatalogWorkspace.tsx
// Управление каталогом и изолированный viewport таблицы с доступным изменением высоты.
import type { AdminModelsController } from '../../controller/useAdminModelsController'
import { useState } from 'react'
import { CatalogTable } from '../ModelControlPlaneViews'
import { ModelIcon } from '../ModelIcon'
import { lifecycleLabel } from '../modelControlPlaneViews/viewPresentationLabels'
import { MODEL_LIFECYCLES } from '../../../../entities/model/api/model/modelEnums'
import CatalogEmptyState from '../CatalogEmptyState'
import { CATALOG_PANEL_MIN_HEIGHT, useCatalogPanelHeight } from './useCatalogPanelHeight'

export function ModelsCatalogWorkspace({ controller }: { controller: AdminModelsController }) {
    const { catalog, effectiveCatalog, runtime, isSuperAdmin, mutationPending,
        handleImportRuntime, setCatalogModal, setHistoryModalEntry,
        importError } = controller
    const [query, setQuery] = useState('')
    const [provider, setProvider] = useState('')
    const [lifecycle, setLifecycle] = useState('')
    const providers = [...new Set(catalog.map(entry => entry.provider))].sort()
    const search = query.trim().toLowerCase()
    const visibleEntries = catalog.filter(entry =>
        (!provider || provider === entry.provider)
        && (!lifecycle || lifecycle === entry.lifecycle)
        && (!search || [entry.displayName, entry.modelKey, entry.provider, entry.providerModelId]
            .some(value => value.toLowerCase().includes(search))))
    const filtered = Boolean(search || provider || lifecycle)
    const {
        regionRef,
        height,
        maxHeight,
        hasOverflow,
        onPointerDown,
        onPointerMove,
        onPointerUp,
        onPointerCancel,
        onKeyDown,
        onDoubleClick,
    } = useCatalogPanelHeight(visibleEntries.length)
    if (!runtime) return null

    return (
        <section className="models-section-stack models-catalog-workspace" aria-labelledby="models-catalog-title">
            <header className="models-section-heading models-section-heading--actions">
                <div>
                    <span className="models-eyebrow">ВЕРСИИ И ХАРАКТЕРИСТИКИ</span>
                    <h2 id="models-catalog-title">Каталог моделей</h2>
                    <p>Последняя сохранённая и действующая версии могут различаться. История доступна для каждой модели.</p>
                </div>
                {isSuperAdmin && catalog.length > 0 && <div className="models-section-actions">
                    <button type="button" className="secondary-button" disabled={mutationPending}
                        onClick={() => { void handleImportRuntime() }}>Добавить подключённую модель в каталог</button>
                    <button type="button" className="btn-primary" disabled={mutationPending}
                        onClick={() => setCatalogModal({ base: null })}>Создать модель</button>
                </div>}
            </header>
            {importError && <div className="models-mutation-error" role="alert">
                <strong>Не удалось добавить подключённую модель в каталог</strong>
                <p>{importError}</p>
                <p>Если версия уже создана вручную, откройте её историю и создайте новую версию. Существующие данные не изменены.</p>
            </div>}
            {!isSuperAdmin && <p className="models-catalog-permission-hint" role="note">
                <ModelIcon name="shield" /> <strong>Каталог изменяет SUPER_ADMIN.</strong> Администратор организации может просматривать версии и изменять правила своей организации.
            </p>}

            <div className="models-catalog-panel">
            {catalog.length > 0 && <div className="models-catalog-filters">
                <label className="models-catalog-query"><span><ModelIcon name="search" /> Поиск модели</span>
                    <input type="search" value={query} placeholder="Название, ключ или провайдер" onChange={event => setQuery(event.target.value)} /></label>
                <label><span>Провайдер</span><select value={provider} onChange={event => setProvider(event.target.value)}>
                    <option value="">Все провайдеры</option>{providers.map(value => <option key={value}>{value}</option>)}
                </select></label>
                <label><span>Статус последней версии</span><select value={lifecycle} onChange={event => setLifecycle(event.target.value)}>
                    <option value="">Все статусы</option>{MODEL_LIFECYCLES.map(value => <option key={value} value={value}>{lifecycleLabel(value)}</option>)}
                </select></label>
                {filtered && <button type="button" className="secondary-button" onClick={() => { setQuery(''); setProvider(''); setLifecycle('') }}>Сбросить</button>}
            </div>}
            <div id="models-catalog-table-viewport" ref={regionRef}
                className={`models-catalog-table-region${visibleEntries.length === 0 ? ' models-catalog-table-region--empty' : ''}`}
                role="region" aria-label="Таблица каталога моделей" tabIndex={0}
                style={visibleEntries.length > 0 ? { maxHeight: `${height}px` } : undefined}>
                {catalog.length === 0 ? <CatalogEmptyState isSuperAdmin={isSuperAdmin} mutationPending={mutationPending}
                    onImportRuntime={() => { void handleImportRuntime() }} onCreateModel={() => setCatalogModal({ base: null })} />
                    : visibleEntries.length === 0 ? <div className="models-filter-empty"><ModelIcon name="search" /><strong>Модели не найдены</strong><span>Измените поиск или сбросьте фильтры.</span></div>
                    : <CatalogTable entries={visibleEntries} effectiveEntries={effectiveCatalog} runtime={runtime}
                        canEdit={isSuperAdmin}
                        onCreateVersion={(entry) => setCatalogModal({ base: entry })}
                        onOpenHistory={setHistoryModalEntry} />}
            </div>
            {/* На десктопе ручка появляется только при вертикальном переполнении. */}
            {visibleEntries.length > 0 && hasOverflow && (
                <div className="models-catalog-resize-handle" role="separator"
                    aria-label="Изменить высоту таблицы каталога"
                    aria-orientation="horizontal"
                    aria-valuemin={CATALOG_PANEL_MIN_HEIGHT}
                    aria-valuemax={maxHeight}
                    aria-valuenow={height}
                    aria-controls="models-catalog-table-viewport"
                    tabIndex={0}
                    onPointerDown={onPointerDown}
                    onPointerMove={onPointerMove}
                    onPointerUp={onPointerUp}
                    onPointerCancel={onPointerCancel}
                    onLostPointerCapture={onPointerCancel}
                    onKeyDown={onKeyDown}
                    onDoubleClick={onDoubleClick}
                    title="Потяните вниз, чтобы увеличить таблицу, вверх — чтобы уменьшить. Стрелки ↑/↓ также меняют высоту; двойной щелчок возвращает автоматическую высоту."
                ><span className="models-catalog-resize-handle__grip" aria-hidden="true" />
                    <span className="models-catalog-resize-handle__label" aria-hidden="true">Размер таблицы</span>
                </div>
            )}
            <div className="models-catalog-summary" aria-label="Сводка каталога" aria-live="polite">
                {filtered ? `Показано: ${visibleEntries.length} из ${catalog.length} · ` : ''}Моделей: {catalog.length} · Действующих версий: {effectiveCatalog.length}
            </div>
            </div>
        </section>
    )
}

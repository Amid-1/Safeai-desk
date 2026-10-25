// frontend/src/features/models/ui/modelControlPlaneViews/CatalogTable.tsx
// Назначение: таблица версий каталога моделей.

import type { ModelCatalogEntry, RuntimeModelStatus } from '../../../../entities/model/api/modelApi'
import {formatDateTime} from '../../../../shared/lib/format'
import { StatusPill } from './viewPresentation'
import { lifecycleTone, lifecycleLabel, catalogSourceLabel, catalogCapabilities, pricingStatusLabel, retentionLabel, trainingUseLabel } from './viewPresentationLabels'
import type { StatusTone } from './viewPresentationLabels'

export function CatalogTable({
    entries,
    effectiveEntries,
    runtime,
    canEdit,
    onCreateVersion,
    onOpenHistory,
}: {
    entries: ModelCatalogEntry[]
    effectiveEntries: ModelCatalogEntry[]
    runtime: RuntimeModelStatus
    canEdit: boolean
    onCreateVersion: (entry: ModelCatalogEntry) => void
    onOpenHistory: (entry: ModelCatalogEntry) => void
}) {
    return (
        <table className="models-catalog-table">
            <thead>
                <tr>
                    <th>Модель</th>
                    <th scope="col">Подключение</th>
                    <th>Статус каталога</th>
                    <th>Лимиты</th>
                    <th>Возможности каталога</th>
                    <th>Стоимость</th>
                    <th>Политика данных</th>
                    <th>Действует с</th>
                    <th>Действия</th>
                </tr>
            </thead>

            <tbody>
                {entries.map((entry) => {
                    const effectiveEntry =
                        effectiveEntries.find(
                            (candidate) =>
                                candidate.modelKey === entry.modelKey,
                        ) ?? null

                    const isEffectiveVersion =
                        effectiveEntry?.id === entry.id

                    /*
                     * /catalog возвращает последнюю версию каждого ключа,
                     * /catalog/effective — действующую версию по времени сервера.
                     * Локальные часы браузера не определяют момент вступления
                     * записи в силу.
                     */
                    const scheduled =
                        !isEffectiveVersion

                    const runtimeMatches =
                        entry.provider === runtime.provider
                        && entry.providerModelId === runtime.model

                    const routeEligibleLifecycle =
                        entry.lifecycle === 'ACTIVE'
                        || entry.lifecycle === 'DEPRECATED'

                    const executableByCurrentRuntime =
                        isEffectiveVersion
                        && routeEligibleLifecycle
                        && runtimeMatches

                    const effectiveStateLabel =
                        isEffectiveVersion
                            ? 'Действует сейчас'
                            : 'Последняя версия пока не действует'

                    const effectiveStateTone: StatusTone =
                        isEffectiveVersion
                            ? 'success'
                            : 'warning'

                    return (
                        <tr key={entry.id}>
                            <td data-label="Модель">
                                <strong>{entry.displayName}</strong>
                                <code>{entry.modelKey}</code>

                                <div className="models-catalog-version-flags">
                                    <StatusPill
                                        tone="neutral"
                                        label={`Последняя сохраненная · v${entry.version}`}
                                    />

                                    {isEffectiveVersion && (
                                        <StatusPill
                                            tone="success"
                                            label={`Действующая · v${entry.version}`}
                                        />
                                    )}
                                </div>

                                <small>
                                    Запись каталога v{entry.version}
                                    {' · '}
                                    {catalogSourceLabel(entry.source)}
                                </small>
                            </td>

                            <td data-label="Подключение">
                                <span>
                                    {entry.provider}
                                    {' / '}
                                    {entry.providerModelId}
                                </span>

                                <StatusPill
                                    tone={
                                        runtimeMatches
                                            ? 'success'
                                            : 'neutral'
                                    }
                                    label={
                                        runtimeMatches
                                            ? 'Совпадает с подключением'
                                            : 'Подключённая модель отличается'
                                    }
                                />

                                {executableByCurrentRuntime && (
                                    <small>
                                        Действующая версия физически
                                        совместима с подключённой моделью
                                    </small>
                                )}
                            </td>

                            <td data-label="Статус каталога">
                                <StatusPill
                                    tone={lifecycleTone(entry.lifecycle)}
                                    label={lifecycleLabel(entry.lifecycle)}
                                />
                            </td>

                            <td data-label="Лимиты">
                                {entry.maxInputTokens.toLocaleString('ru-RU')}
                                {' / '}
                                {entry.maxOutputTokens.toLocaleString('ru-RU')}
                                <small>вход / выход</small>
                            </td>

                            <td data-label="Возможности каталога">
                                {catalogCapabilities(entry)}
                                {entry.capabilities.length > 0 && (
                                    <small>
                                        Сведения каталога; фактическое исполнение
                                        проверяется у провайдера отдельно
                                    </small>
                                )}
                            </td>

                            <td data-label="Стоимость">
                                <strong>
                                    {pricingStatusLabel(entry.pricingStatus)}
                                </strong>
                                <small>
                                    {entry.pricingComplete
                                        ? 'Данные о стоимости полные'
                                        : 'Данные о стоимости неполные'}
                                    {entry.pricingVersion
                                        ? ` · ${entry.pricingVersion}`
                                        : ''}
                                </small>
                            </td>

                            <td data-label="Политика данных">
                                <span>
                                    Хранение: {' '}
                                    {retentionLabel(entry.retentionStatus)}
                                </span>
                                <small>
                                    Обучение: {' '}
                                    {trainingUseLabel(entry.trainingUseStatus)}
                                </small>
                            </td>

                            <td data-label="Действует с">
                                {formatDateTime(entry.effectiveFrom)}

                                <div className="models-catalog-effective-state">
                                    <StatusPill
                                        tone={effectiveStateTone}
                                        label={effectiveStateLabel}
                                    />
                                </div>

                                {scheduled && effectiveEntry && (
                                    <small>
                                        Сейчас действует версия {' '}
                                        {effectiveEntry.version}
                                    </small>
                                )}

                                {scheduled && !effectiveEntry && (
                                    <small>
                                        До даты вступления в силу модель не участвует
                                        в маршрутизации
                                    </small>
                                )}
                            </td>

                            <td data-label="Действия">
                                <div className="models-catalog-row-actions">
                                    <button
                                        type="button"
                                        onClick={() => {
                                            onOpenHistory(entry)
                                        }}
                                    >
                                        История версий
                                    </button>

                                    {canEdit && (
                                        <button
                                            type="button"
                                            onClick={() => {
                                                onCreateVersion(entry)
                                            }}
                                        >
                                            Новая версия
                                        </button>
                                    )}
                                </div>
                            </td>
                        </tr>
                    )
                })}
            </tbody>
        </table>
    )
}

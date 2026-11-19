// frontend/src/features/models/ui/modelControlPlaneViews/CatalogTable.tsx
// Назначение: таблица версий каталога моделей.

import type { ModelCatalogEntry, RuntimeModelStatus } from '../../../../entities/model/api/modelApi'
import {formatDateTime} from '../../../../shared/lib/format'
import { StatusPill } from './viewPresentation'
import { lifecycleTone, lifecycleLabel, catalogSourceLabel, pricingStatusLabel, retentionLabel, trainingUseLabel } from './viewPresentationLabels'
import type { StatusTone } from './viewPresentationLabels'
import { ModelIcon } from '../ModelIcon'
import type { ModelIconName } from '../ModelIcon'

function CatalogCapabilities({ entry }: { entry: ModelCatalogEntry }) {
    const modalities = [...new Set([...entry.inputModalities, ...entry.outputModalities])]
    const modalityLabels = { TEXT: 'Текст', IMAGE: 'Изображения', AUDIO: 'Аудио' }
    const modalityIcons: Record<string, ModelIconName> = { TEXT: 'text', IMAGE: 'image', AUDIO: 'audio' }
    const capabilityLabels = { TOOLS: 'Инструменты', VISION: 'Распознавание изображений', STRUCTURED_OUTPUT: 'Структурированный ответ' }
    const capabilityIcons: Record<string, ModelIconName> = { TOOLS: 'tools', VISION: 'image', STRUCTURED_OUTPUT: 'code' }
    return <div className="models-capabilities">
        {modalities.map(value => <span key={value} title={`${modalityLabels[value]}: ${entry.inputModalities.includes(value) ? 'вход' : ''}${entry.inputModalities.includes(value) && entry.outputModalities.includes(value) ? ' / ' : ''}${entry.outputModalities.includes(value) ? 'выход' : ''}`}>
            <ModelIcon name={modalityIcons[value]} />{modalityLabels[value]}
        </span>)}
        {entry.capabilities.map(value => <span key={value}><ModelIcon name={capabilityIcons[value]} />{capabilityLabels[value]}</span>)}
        {!modalities.length && !entry.capabilities.length && <small>Не заявлены</small>}
    </div>
}

export function CatalogTable({
    entries,
    effectiveEntries,
    runtime,
    canEdit,
    onCreateVersion,
    onOpenHistory,
    organizationId,
}: {
    entries: ModelCatalogEntry[]
    effectiveEntries: ModelCatalogEntry[]
    runtime: RuntimeModelStatus
    canEdit: boolean
    organizationId?: string
    onCreateVersion: (entry: ModelCatalogEntry) => void
    onOpenHistory: (entry: ModelCatalogEntry) => void
}) {
    return (
        <table className="models-catalog-table">
            <thead>
                <tr>
                    <th scope="col">Модель</th>
                    <th scope="col">Подключение</th>
                    <th scope="col">Статус каталога</th>
                    <th scope="col">Лимиты</th>
                    <th scope="col">Возможности каталога</th>
                    <th scope="col">Стоимость</th>
                    <th scope="col">Политика данных</th>
                    <th scope="col">Действует с</th>
                    <th scope="col">Действия</th>
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

                    const effectiveRuntimeMatches = effectiveEntry !== null
                        && effectiveEntry.provider === runtime.provider
                        && effectiveEntry.providerModelId === runtime.model

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
                                <button type="button" className="admin-related-link models-catalog-model-name" onClick={() => onOpenHistory(entry)}><ModelIcon name="cpu" />{entry.displayName}</button>
                                <code>{entry.modelKey}</code>

                                <div className="models-catalog-version-flags">
                                    {isEffectiveVersion ? <StatusPill tone="success" label={`Последняя и действующая · v${entry.version}`} /> : <StatusPill
                                        tone="neutral"
                                        label={`Последняя сохраненная · v${entry.version}`}
                                    />}
                                    {!isEffectiveVersion && effectiveEntry && <StatusPill tone="success" label={`Действующая · v${effectiveEntry.version}`} />}
                                </div>

                                <small>
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
                                            : 'Не совпадает с подключением'
                                    }
                                />

                                {scheduled && effectiveEntry && (
                                    <small>
                                        Действующая v{effectiveEntry.version}: {effectiveRuntimeMatches
                                            ? 'совпадает с подключением'
                                            : 'не совпадает с подключением'}
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
                                <div className="models-token-pair"><span><small>Вход</small><strong>{entry.maxInputTokens.toLocaleString('ru-RU')}</strong></span>
                                    <span><small>Выход</small><strong>{entry.maxOutputTokens.toLocaleString('ru-RU')}</strong></span></div>
                            </td>

                            <td data-label="Возможности каталога">
                                <CatalogCapabilities entry={entry} />
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
                                        <ModelIcon name="history" />История версий
                                    </button>
                                    <a className="admin-related-link" href={`/admin/usage?model=${encodeURIComponent(entry.providerModelId)}${organizationId ? '&organizationId=' + encodeURIComponent(organizationId) : ''}`}>Использование и расходы</a>

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

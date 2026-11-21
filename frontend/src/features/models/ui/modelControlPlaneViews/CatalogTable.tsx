// frontend/src/features/models/ui/modelControlPlaneViews/CatalogTable.tsx
// Назначение: таблица версий каталога моделей.

import type { ModelCatalogEntry, RuntimeModelStatus } from '../../../../entities/model/api/modelApi'
import {formatDateTime} from '../../../../shared/lib/format'
import { InfoHint, StatusPill } from './viewPresentation'
import { lifecycleTone, lifecycleLabel, catalogSourceLabel, pricingStatusLabel, retentionLabel, trainingUseLabel } from './viewPresentationLabels'
import type { StatusTone } from './viewPresentationLabels'
import { ModelIcon } from '../ModelIcon'
import type { ModelIconName } from '../ModelIcon'
import { runtimeDataGuarantees } from '../modelConfigurationReadiness'

const capabilityHint = 'Заявленные форматы входа и выхода и функции модели. Наличие в каталоге не подтверждает поддержку текущим подключением.'
const privacyHint = 'Условия обработки данных AI-провайдером. Декларации каталога не подтверждают гарантии подключения и не описывают хранение истории в SafeAI Desk.'

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
        {entry.capabilities.map(value => <span key={value} title={capabilityLabels[value]}><ModelIcon name={capabilityIcons[value]} />{capabilityLabels[value]}</span>)}
        {!modalities.length && !entry.capabilities.length && <small>Не заявлены</small>}
    </div>
}

function CatalogPrivacy({ entry, runtime, matches }: { entry: ModelCatalogEntry; runtime: RuntimeModelStatus; matches: boolean }) {
    const retentionTone: StatusTone = entry.retentionStatus === 'ZERO_DATA_RETENTION' ? 'success'
        : entry.retentionStatus === 'NOT_DECLARED' ? 'warning' : 'neutral'
    const trainingTone: StatusTone = entry.trainingUseStatus === 'NOT_USED' || entry.trainingUseStatus === 'CONTRACTUAL_NO_TRAINING'
        ? 'success' : entry.trainingUseStatus === 'MAY_BE_USED' ? 'danger' : 'warning'
    const guarantees = runtimeDataGuarantees(runtime)
    const evidence = runtime.dataAssurance
    return <div className="models-catalog-privacy">
        <div className="models-catalog-privacy__group">
            <small>Заявлено в каталоге</small>
            <StatusPill tone={retentionTone} label={`Хранение: ${entry.retentionStatus === 'NOT_DECLARED' ? 'Не указано' : retentionLabel(entry.retentionStatus)}`} />
            <StatusPill tone={trainingTone} label={`Обучение: ${entry.trainingUseStatus === 'NOT_DECLARED' ? 'Не указано' : trainingUseLabel(entry.trainingUseStatus)}`} />
        </div>
        <div className="models-catalog-privacy__group models-catalog-privacy__evidence">
            <small>Гарантии подключения</small>
            {!matches ? <StatusPill tone="neutral" label="Нет совпадающего подключения" />
                : !runtime.enabled ? <StatusPill tone="neutral" label="Подключение отключено" />
                : <>
                    <StatusPill tone={guarantees.noTraining ? 'success' : 'warning'}
                        label={guarantees.noTraining ? 'Запрет обучения подтверждён' : 'Запрет обучения не подтверждён'} />
                    <StatusPill tone={guarantees.zeroDataRetention ? 'success' : 'warning'}
                        label={guarantees.zeroDataRetention ? 'Нулевое хранение подтверждено' : 'Нулевое хранение не подтверждено'} />
                    {(guarantees.noTraining || guarantees.zeroDataRetention) && <small>
                        {runtime.provider === 'mock' && evidence?.source === 'LOCAL_MOCK' ? 'Локальная тестовая модель'
                            : evidence?.expiresAt ? `Срок до: ${formatDateTime(evidence.expiresAt)}` : ''}
                    </small>}
                </>}
        </div>
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
                    <th scope="col">Возможности модели <InfoHint text={capabilityHint} /></th>
                    <th scope="col">Стоимость</th>
                    <th scope="col">Конфиденциальность данных <InfoHint text={privacyHint} /></th>
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

                            <td data-label="Возможности модели">
                                <CatalogCapabilities entry={entry} />
                                <small>Заявлено в каталоге</small>
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

                            <td data-label="Конфиденциальность данных">
                                <CatalogPrivacy entry={entry} runtime={runtime} matches={runtimeMatches} />
                            </td>

                            <td data-label="Действует с">
                                {formatDateTime(entry.effectiveFrom)}

                                {scheduled && <div className="models-catalog-effective-state">
                                    <StatusPill
                                        tone="warning"
                                        label="Последняя версия пока не действует"
                                    />
                                </div>}

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
                                        type="button" className="secondary-button"
                                        onClick={() => {
                                            onOpenHistory(entry)
                                        }}
                                    >
                                        <ModelIcon name="history" />История версий
                                    </button>
                                    <a className="secondary-button models-catalog-action-link" href={`/admin/usage?model=${encodeURIComponent(entry.providerModelId)}${organizationId ? '&organizationId=' + encodeURIComponent(organizationId) : ''}`}>Использование и расходы</a>

                                    {canEdit && (
                                        <button
                                            type="button" className="secondary-button"
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

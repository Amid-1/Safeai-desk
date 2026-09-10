/* ============================================================
   frontend/src/components/admin/models/ModelCatalogHistoryModal.tsx
   ============================================================ */
import {
    useEffect,
    useMemo,
    useState,
} from 'react'
import {
    getEffectiveModelCatalog,
} from '../../../api/modelApi'
import type {
    ModelCatalogEntry,
    RuntimeModelStatus,
} from '../../../api/modelApi'
import {
    getModelCatalogHistory,
} from '../../../api/modelCatalogHistoryApi'
import {
    getApiErrorMessage,
} from '../../../api/http'
import {
    formatDateTime,
} from '../../../utils/format'
import Modal from '../../Modal'
import type {
    ModalResizeOptions,
} from '../../Modal'
import {
    ErrorState,
    LoadingState,
} from '../../StateBlock'
import './ModelControlPlaneModalShell.css'
import './ModelCatalogHistoryModal.css'

const HISTORY_MODAL_RESIZE: ModalResizeOptions = {
    initialWidth: 1040,
    initialHeight: 720,
    minWidth: 700,
    minHeight: 480,
    maxWidth: 1480,
    maxHeight: 980,
    scaleContent: true,
    minScale: 0.82,
    maxScale: 1.16,
}

type HistoryState =
    | 'latest'
    | 'effective'
    | 'scheduled'
    | 'historical'
    | 'unknown'

function lifecycleLabel(
    lifecycle: ModelCatalogEntry['lifecycle'],
): string {
    switch (lifecycle) {
        case 'ACTIVE':
            return 'Активна'
        case 'DEPRECATED':
            return 'Устаревает'
        case 'DISABLED':
            return 'Отключена'
        case 'RETIRED':
            return 'Выведена'
    }
}

function retentionLabel(
    status: ModelCatalogEntry['retentionStatus'],
): string {
    switch (status) {
        case 'NOT_DECLARED':
            return 'Не заявлено'
        case 'STANDARD':
            return 'Стандартное хранение'
        case 'ZERO_DATA_RETENTION':
            return 'Без хранения после запроса'
        case 'CUSTOM':
            return 'Особые условия'
    }
}

function trainingLabel(
    status: ModelCatalogEntry['trainingUseStatus'],
): string {
    switch (status) {
        case 'NOT_DECLARED':
            return 'Не заявлено'
        case 'NOT_USED':
            return 'Не используется для обучения'
        case 'MAY_BE_USED':
            return 'Может использоваться для обучения'
        case 'CONTRACTUAL_NO_TRAINING':
            return 'Обучение запрещено договором'
    }
}

function sourceLabel(
    source: ModelCatalogEntry['source'],
): string {
    switch (source) {
        case 'RUNTIME_IMPORT':
            return 'Добавлена из Runtime'
        case 'MANUAL':
            return 'Создана вручную'
        case 'MIGRATED':
            return 'Перенесена миграцией'
    }
}

function capabilitiesLabel(
    entry: ModelCatalogEntry,
): string {
    const values = ['Текст']

    for (const capability of entry.capabilities) {
        switch (capability) {
            case 'TOOLS':
                values.push('Инструменты')
                break
            case 'VISION':
                values.push('Изображения')
                break
            case 'STRUCTURED_OUTPUT':
                values.push('Структурированный ответ')
                break
        }
    }

    return values.join(' · ')
}

function modalitiesLabel(
    entry: ModelCatalogEntry,
): string {
    const label = (value: ModelCatalogEntry['inputModalities'][number]) => {
        switch (value) {
            case 'TEXT':
                return 'Текст'
            case 'IMAGE':
                return 'Изображения'
            case 'AUDIO':
                return 'Аудио'
        }
    }

    return (
        `Вход: ${entry.inputModalities.map(label).join(', ')}`
        + ` · Выход: ${entry.outputModalities.map(label).join(', ')}`
    )
}

function pricingLabel(
    entry: ModelCatalogEntry,
): string {
    switch (entry.pricingStatus) {
        case 'FREE':
            return 'Бесплатно'
        case 'CONFIGURED':
            return 'Стоимость настроена'
        case 'UNPRICED':
            return 'Стоимость не указана'
        case 'INCOMPLETE':
            return 'Данные о стоимости неполные'
    }
}

function versionState(
    entry: ModelCatalogEntry,
    latestEntry: ModelCatalogEntry,
    effectiveEntry: ModelCatalogEntry | null,
    effectiveKnown: boolean,
): HistoryState[] {
    const states: HistoryState[] = []

    if (entry.id === latestEntry.id) {
        states.push('latest')
    }

    if (!effectiveKnown) {
        states.push('unknown')
        return states
    }

    if (effectiveEntry?.id === entry.id) {
        states.push('effective')
        return states
    }

    if (
        effectiveEntry === null
        || entry.version > effectiveEntry.version
    ) {
        states.push('scheduled')
        return states
    }

    states.push('historical')
    return states
}

function StateBadge({
    state,
}: {
    state: HistoryState
}) {
    const label =
        state === 'latest'
            ? 'Последняя версия'
            : state === 'effective'
                ? 'Действующая версия'
                : state === 'scheduled'
                    ? 'Запланирована'
                    : state === 'historical'
                        ? 'Историческая'
                        : 'Статус действия недоступен'

    return (
        <span
            className={
                `models-history-state models-history-state--${state}`
            }
        >
            {label}
        </span>
    )
}

type VersionDiff = {
    label: string
    before: string
    after: string
}

function listValue(values: readonly string[]): string {
    return values.length > 0
        ? [...values].sort().join(', ')
        : '—'
}

function nullableValue(value: string | number | null): string {
    return value === null
        ? '—'
        : String(value)
}

function buildVersionDiff(
    current: ModelCatalogEntry,
    previous: ModelCatalogEntry,
): VersionDiff[] {
    const candidates: VersionDiff[] = [
        {
            label: 'Runtime target',
            before: `${previous.provider} / ${previous.providerModelId}`,
            after: `${current.provider} / ${current.providerModelId}`,
        },
        {
            label: 'Название',
            before: previous.displayName,
            after: current.displayName,
        },
        {
            label: 'Lifecycle',
            before: previous.lifecycle,
            after: current.lifecycle,
        },
        {
            label: 'Лимит входа',
            before: String(previous.maxInputTokens),
            after: String(current.maxInputTokens),
        },
        {
            label: 'Лимит выхода',
            before: String(previous.maxOutputTokens),
            after: String(current.maxOutputTokens),
        },
        {
            label: 'Capabilities',
            before: listValue(previous.capabilities),
            after: listValue(current.capabilities),
        },
        {
            label: 'Input modalities',
            before: listValue(previous.inputModalities),
            after: listValue(current.inputModalities),
        },
        {
            label: 'Output modalities',
            before: listValue(previous.outputModalities),
            after: listValue(current.outputModalities),
        },
        {
            label: 'Хранение данных',
            before: `${previous.retentionStatus} / ${nullableValue(previous.retentionDays)}`,
            after: `${current.retentionStatus} / ${nullableValue(current.retentionDays)}`,
        },
        {
            label: 'Использование для обучения',
            before: previous.trainingUseStatus,
            after: current.trainingUseStatus,
        },
        {
            label: 'Pricing status',
            before: `${previous.pricingStatus} / complete=${previous.pricingComplete}`,
            after: `${current.pricingStatus} / complete=${current.pricingComplete}`,
        },
        {
            label: 'Input $/1M',
            before: nullableValue(previous.inputUsdPer1mTokens),
            after: nullableValue(current.inputUsdPer1mTokens),
        },
        {
            label: 'Cached input $/1M',
            before: nullableValue(previous.cachedInputUsdPer1mTokens),
            after: nullableValue(current.cachedInputUsdPer1mTokens),
        },
        {
            label: 'Cache write $/1M',
            before: nullableValue(previous.cacheWriteInputUsdPer1mTokens),
            after: nullableValue(current.cacheWriteInputUsdPer1mTokens),
        },
        {
            label: 'Output $/1M',
            before: nullableValue(previous.outputUsdPer1mTokens),
            after: nullableValue(current.outputUsdPer1mTokens),
        },
        {
            label: 'Pricing version',
            before: nullableValue(previous.pricingVersion),
            after: nullableValue(current.pricingVersion),
        },
        {
            label: 'Extra pricing',
            before: previous.extraPricingJson,
            after: current.extraPricingJson,
        },
        {
            label: 'Вступает в силу',
            before: previous.effectiveFrom,
            after: current.effectiveFrom,
        },
    ]

    return candidates.filter(
        (item) => item.before !== item.after,
    )
}

export function ModelCatalogHistoryModal({
    modelKey,
    displayName,
    runtime,
    onClose,
}: {
    modelKey: string
    displayName: string
    runtime: RuntimeModelStatus
    onClose: () => void
}) {
    const [versions, setVersions] =
        useState<ModelCatalogEntry[]>([])
    const [effectiveEntry, setEffectiveEntry] =
        useState<ModelCatalogEntry | null>(null)
    const [effectiveKnown, setEffectiveKnown] =
        useState(false)
    const [effectiveWarning, setEffectiveWarning] =
        useState('')
    const [loading, setLoading] =
        useState(true)
    const [error, setError] =
        useState('')

    useEffect(() => {
        const controller = new AbortController()

        setLoading(true)
        setError('')

        setEffectiveKnown(false)
        setEffectiveWarning('')

        void Promise.allSettled([
            getModelCatalogHistory(
                modelKey,
                {
                    signal: controller.signal,
                },
            ),
            getEffectiveModelCatalog({
                signal: controller.signal,
            }),
        ])
            .then(([historyResult, effectiveResult]) => {
                if (controller.signal.aborted) {
                    return
                }

                if (historyResult.status === 'rejected') {
                    throw historyResult.reason
                }

                setVersions(historyResult.value)

                if (effectiveResult.status === 'fulfilled') {
                    const normalizedModelKey =
                        modelKey.trim().toLowerCase()

                    setEffectiveEntry(
                        effectiveResult.value.find(
                            (entry) =>
                                entry.modelKey === normalizedModelKey,
                        ) ?? null,
                    )
                    setEffectiveKnown(true)
                } else {
                    setEffectiveEntry(null)
                    setEffectiveKnown(false)
                    setEffectiveWarning(
                        'История загружена, но серверный статус действующей версии сейчас недоступен. Интерфейс не будет угадывать его по часам браузера.',
                    )
                }
            })
            .catch((failure) => {
                if (!controller.signal.aborted) {
                    setError(
                        getApiErrorMessage(
                            failure,
                            'Не удалось загрузить историю версий модели.',
                        ),
                    )
                }
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setLoading(false)
                }
            })

        return () => {
            controller.abort()
        }
    }, [modelKey])

    const sortedVersions = useMemo(
        () => [...versions].sort(
            (left, right) =>
                right.version - left.version,
        ),
        [versions],
    )

    const latestEntry =
        sortedVersions[0] ?? null

    return (
        <Modal
            title={`История версий: ${displayName}`}
            onClose={onClose}
            closeOnBackdrop={false}
            closeOnEscape={true}
            size="lg"
            className="models-history-modal"
            resize={HISTORY_MODAL_RESIZE}
        >
            <div className="models-history-modal__intro">
                <div>
                    <span>Модель</span>
                    <strong>{displayName}</strong>
                    <code>{modelKey}</code>
                </div>

                <p>
                    Здесь показана полная цепочка неизменяемых версий.
                    Статус «Действующая версия» определяется сервером
                    на момент открытия окна.
                </p>
            </div>

            {loading && (
                <LoadingState message="Загрузка истории версий..." />
            )}

            {error && (
                <ErrorState
                    message={error}
                    variant="inline"
                />
            )}

            {!error && effectiveWarning && (
                <div
                    className="models-history-modal__warning"
                    role="status"
                >
                    {effectiveWarning}
                </div>
            )}

            {!loading && !error && sortedVersions.length === 0 && (
                <div className="models-history-modal__empty">
                    История для этой модели пока пуста.
                </div>
            )}

            {!loading
                && !error
                && latestEntry
                && sortedVersions.length > 0
                && (
                    <ol className="models-history-list">
                        {sortedVersions.map((entry, index) => {
                            const previousEntry =
                                sortedVersions[index + 1] ?? null
                            const diffs = previousEntry
                                ? buildVersionDiff(entry, previousEntry)
                                : []

                            const states = versionState(
                                entry,
                                latestEntry,
                                effectiveEntry,
                                effectiveKnown,
                            )

                            const runtimeMatches =
                                entry.provider === runtime.provider
                                && entry.providerModelId === runtime.model

                            return (
                                <li
                                    key={entry.id}
                                    className="models-history-card"
                                >
                                    <div className="models-history-card__rail" />

                                    <div className="models-history-card__header">
                                        <div>
                                            <span>
                                                Версия {entry.version}
                                            </span>
                                            <strong>
                                                {entry.provider}
                                                {' / '}
                                                {entry.providerModelId}
                                            </strong>
                                        </div>

                                        <div className="models-history-card__badges">
                                            {states.map((state) => (
                                                <StateBadge
                                                    key={state}
                                                    state={state}
                                                />
                                            ))}

                                            {runtimeMatches && (
                                                <span className="models-history-state models-history-state--runtime">
                                                    Совпадает с Runtime
                                                </span>
                                            )}
                                        </div>
                                    </div>

                                    <dl className="models-history-card__facts">
                                        <div>
                                            <dt>Статус каталога</dt>
                                            <dd>{lifecycleLabel(entry.lifecycle)}</dd>
                                        </div>

                                        <div>
                                            <dt>Лимиты</dt>
                                            <dd>
                                                {entry.maxInputTokens.toLocaleString('ru-RU')}
                                                {' / '}
                                                {entry.maxOutputTokens.toLocaleString('ru-RU')}
                                            </dd>
                                        </div>

                                        <div>
                                            <dt>Возможности</dt>
                                            <dd>{capabilitiesLabel(entry)}</dd>
                                        </div>

                                        <div>
                                            <dt>Стоимость</dt>
                                            <dd>{pricingLabel(entry)}</dd>
                                            {entry.pricingVersion && (
                                                <small>
                                                    Тариф: {entry.pricingVersion}
                                                </small>
                                            )}
                                        </div>

                                        <div>
                                            <dt>Хранение данных</dt>
                                            <dd>{retentionLabel(entry.retentionStatus)}</dd>
                                        </div>

                                        <div>
                                            <dt>Использование для обучения</dt>
                                            <dd>{trainingLabel(entry.trainingUseStatus)}</dd>
                                        </div>

                                        <div>
                                            <dt>Типы данных</dt>
                                            <dd>{modalitiesLabel(entry)}</dd>
                                        </div>

                                        <div>
                                            <dt>Вступает в силу</dt>
                                            <dd>{formatDateTime(entry.effectiveFrom)}</dd>
                                        </div>
                                    </dl>

                                    <details className="models-history-card__diff">
                                        <summary>
                                            {previousEntry
                                                ? `Изменения относительно версии ${previousEntry.version} · ${diffs.length}`
                                                : 'Базовая версия'}
                                        </summary>

                                        {previousEntry ? (
                                            diffs.length > 0 ? (
                                                <dl className="models-history-card__diff-list">
                                                    {diffs.map((diff) => (
                                                        <div key={diff.label}>
                                                            <dt>{diff.label}</dt>
                                                            <dd>
                                                                <span>{diff.before}</span>
                                                                <span aria-hidden="true">→</span>
                                                                <strong>{diff.after}</strong>
                                                            </dd>
                                                        </div>
                                                    ))}
                                                </dl>
                                            ) : (
                                                <p>
                                                    Семантические параметры не изменились.
                                                </p>
                                            )
                                        ) : (
                                            <p>
                                                Это первая сохранённая версия модели.
                                            </p>
                                        )}
                                    </details>

                                    <div className="models-history-card__footer">
                                        <span>{sourceLabel(entry.source)}</span>
                                        <span>
                                            Создана: {formatDateTime(entry.createdAt)}
                                        </span>
                                    </div>
                                </li>
                            )
                        })}
                    </ol>
                )}
        </Modal>
    )
}

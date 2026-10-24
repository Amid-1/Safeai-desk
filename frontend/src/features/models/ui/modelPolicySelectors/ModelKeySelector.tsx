// frontend/src/features/models/ui/modelPolicySelectors/ModelKeySelector.tsx
// Доступный интерфейс выбора allowlist и denylist моделей.

import {useEffect, useMemo, useState} from 'react'
import {useModelKeySelector} from './useModelKeySelector'
import type {ModelKeySelectorProps} from './selectorTypes'
import {
    MAX_POLICY_MODEL_KEYS,
    modelExecutionLabel,
    conflictMessage,
    selectorDescription,
    validateModelKey,
} from './selectorSupport'

export function ModelKeySelector({
    label,
    hint,
    kind,
    catalog,
    effectiveCatalog,
    runtime,
    value,
    conflictingValue,
    disabled = false,
    inputRef,
    onChange,
    onTransferFromOppositeList,
    onInteractionStateChange,
}: ModelKeySelectorProps) {
    const [selectedSearch, setSelectedSearch] = useState('')
    const [pendingTransferKey, setPendingTransferKey] = useState<string | null>(null)
    const {
        controlRef, query, setQuery, error, setError,
        expanded, setExpanded, activeIndex, setActiveIndex,
        selectedKeys, conflictingKeys, catalogLookup,
        filteredCatalog, matchingCatalogCount, manualCandidate, commitKey, commitMany,
        removeKey, optionCount, activeOptionId, inputId, listboxId,
    } = useModelKeySelector({
        label, hint, kind, catalog, effectiveCatalog, runtime,
        value, conflictingValue, disabled, inputRef,
        onChange, onInteractionStateChange,
    })
    useEffect(() => {
        if (!expanded) return
        document.getElementById(inputId)?.scrollIntoView?.({block: 'start'})
    }, [expanded, inputId])

    useEffect(() => {
        if (!expanded || !activeOptionId) return
        document.getElementById(activeOptionId)?.scrollIntoView?.({block: 'nearest'})
    }, [expanded, activeOptionId])

    const dense = selectedKeys.length >= 5
    const conflictingCatalogEntries = filteredCatalog.filter(entry =>
        conflictingKeys.has(entry.modelKey.toLowerCase()),
    )
    const transferEntry = pendingTransferKey
        ? conflictingCatalogEntries.find(entry => entry.modelKey.toLowerCase() === pendingTransferKey)
        : undefined
    const sourceWillBecomeEmpty = conflictingKeys.size === 1
    const destinationLabel = kind === 'deny' ? 'запрещённые' : 'разрешённые'
    const sourceLabel = kind === 'deny' ? 'разрешённых' : 'запрещённых'

    const selectedQuery = selectedSearch.trim().toLowerCase()
    const visibleSelectedKeys = useMemo(() => selectedKeys.filter((key) => {
        if (!selectedQuery) return true
        const entry = catalogLookup.get(key)
        return key.includes(selectedQuery)
            || Boolean(entry?.displayName.toLowerCase().includes(selectedQuery))
            || Boolean(entry?.provider.toLowerCase().includes(selectedQuery))
    }), [selectedKeys, selectedQuery, catalogLookup])

    return (
        <div className="models-model-selector">
            <div className="models-model-selector__heading">
                <label
                    htmlFor={inputId}
                    className="models-model-selector__label"
                >
                    {label}
                </label>

                <span className="models-model-selector__count">
                    {selectedKeys.length}
                    {' / '}
                    {MAX_POLICY_MODEL_KEYS}
                </span>
            </div>

            <p className="models-model-selector__description">
                {selectorDescription(kind)}
            </p>

            <div
                ref={controlRef}
                className={[
                    'models-model-selector__control',
                    error
                        ? 'models-model-selector__control--error'
                        : '',
                ].filter(Boolean).join(' ')}
            >
                {selectedKeys.length >= 12 && (
                    <label className="models-model-selector__selected-search">
                        <span>Поиск среди выбранных ({selectedKeys.length})</span>
                        <input
                            type="search"
                            value={selectedSearch}
                            disabled={disabled}
                            onChange={(event) => setSelectedSearch(event.target.value)}
                            placeholder="Найти в выбранных моделях"
                        />
                    </label>
                )}
                {selectedKeys.length > 0 && (
                    <div
                        className={[
                            'models-model-selector__chips',
                            dense ? 'models-model-selector__chips--dense' : '',
                        ].filter(Boolean).join(' ')}
                        role="list"
                        aria-label={`${label}: выбранные модели`}
                        tabIndex={dense ? 0 : undefined}
                    >
                        {visibleSelectedKeys.map((key) => {
                            const entry = catalogLookup.get(key)

                            return (
                                <span
                                    key={key}
                                    role="listitem"
                                    className={[
                                        'models-model-chip',
                                        !entry
                                            ? 'models-model-chip--warning'
                                            : '',
                                    ].filter(Boolean).join(' ')}
                                    title={modelExecutionLabel(
                                        key,
                                        catalog,
                                        effectiveCatalog,
                                        runtime,
                                    )}
                                >
                                    {entry && (
                                        <strong>
                                            {entry.displayName}
                                        </strong>
                                    )}

                                    <code>{key}</code>

                                    <span className="models-model-chip__state">
                                        {modelExecutionLabel(
                                            key,
                                            catalog,
                                            effectiveCatalog,
                                            runtime,
                                        )}
                                    </span>

                                    <button
                                        type="button"
                                        disabled={disabled}
                                        aria-label={`Удалить ${key}`}
                                        title={`Удалить ${key}`}
                                        onClick={() => {
                                            removeKey(key)
                                        }}
                                    >
                                        ×
                                    </button>
                                </span>
                            )
                        })}
                        {visibleSelectedKeys.length === 0 && (
                            <p className="models-model-selector__selected-empty">
                                Среди выбранных моделей совпадений нет.
                            </p>
                        )}
                    </div>
                )}
                {dense && (
                    <p className="models-model-selector__scroll-hint">
                        {selectedSearch.trim()
                            ? `Найдено среди выбранных: ${visibleSelectedKeys.length} из ${selectedKeys.length}.`
                            : `Выбрано ${selectedKeys.length} моделей. Список прокручивается внутри этой области.`}
                    </p>
                )}

                <div className="models-model-selector__search-row">
                    <span
                        className="models-model-selector__search-icon"
                        aria-hidden="true"
                    >
                        ⌕
                    </span>

                    <input
                        id={inputId}
                        ref={inputRef}
                        type="text"
                        autoComplete="off"
                        spellCheck={false}
                        disabled={disabled}
                        value={query}
                        role="combobox"
                        aria-expanded={expanded}
                        aria-controls={listboxId}
                        aria-autocomplete="list"
                        aria-activedescendant={activeOptionId}
                        aria-invalid={Boolean(error)}
                        placeholder="Найти в каталоге или ввести ключ модели"
                        onFocus={() => {
                            setExpanded(true)
                        }}
                        onChange={(event) => {
                            setPendingTransferKey(null)
                            setQuery(event.target.value)
                            setError('')
                            setExpanded(true)
                            setActiveIndex(0)
                        }}
                        onPaste={(event) => {
                            const pasted =
                                event.clipboardData.getData('text')

                            if (/[\n\r,;]/.test(pasted)) {
                                event.preventDefault()
                                commitMany(pasted)
                            }
                        }}
                        onKeyDown={(event) => {
                            if (
                                event.key === 'ArrowDown'
                                && optionCount > 0
                            ) {
                                event.preventDefault()
                                setExpanded(true)
                                setActiveIndex((current) =>
                                    Math.min(
                                        current + 1,
                                        optionCount - 1,
                                    ),
                                )
                                return
                            }

                            if (
                                event.key === 'ArrowUp'
                                && optionCount > 0
                            ) {
                                event.preventDefault()
                                setActiveIndex((current) =>
                                    Math.max(current - 1, 0),
                                )
                                return
                            }

                            if (event.key === 'Escape') {
                                setExpanded(false)
                                setError('')
                                return
                            }

                            if (
                                event.key === ','
                                || event.key === ';'
                            ) {
                                event.preventDefault()
                                if (query.trim()) {
                                    commitKey(query)
                                }
                                return
                            }

                            if (event.key !== 'Enter') {
                                return
                            }

                            event.preventDefault()

                            const normalizedExactQuery =
                                validateModelKey(query)

                            if (
                                normalizedExactQuery.valid
                                && (
                                    catalogLookup.has(
                                        normalizedExactQuery.value,
                                    )
                                    || selectedKeys.includes(
                                        normalizedExactQuery.value,
                                    )
                                    || (
                                        manualCandidate
                                            === normalizedExactQuery.value
                                        && filteredCatalog.length === 0
                                    )
                                )
                            ) {
                                commitKey(
                                    normalizedExactQuery.value,
                                )
                                return
                            }

                            const activeCatalogEntry =
                                filteredCatalog[activeIndex]

                            if (
                                expanded
                                && activeCatalogEntry
                            ) {
                                commitKey(
                                    activeCatalogEntry.modelKey,
                                )
                                return
                            }

                            if (
                                expanded
                                && manualCandidate
                                && activeIndex
                                    === filteredCatalog.length
                            ) {
                                commitKey(manualCandidate)
                                return
                            }

                            if (query.trim()) {
                                commitKey(query)
                            }
                        }}
                    />
                </div>

                {expanded && (
                    <div className="models-model-selector__results">
                        <p className="models-model-selector__result-count" role="status">
                            {matchingCatalogCount > filteredCatalog.length
                                ? `Показаны первые ${filteredCatalog.length} из ${matchingCatalogCount}. Уточните поиск для остальных.`
                                : `Найдено в каталоге: ${matchingCatalogCount}.`}
                        </p>
                        <div
                            id={listboxId}
                            className="models-model-selector__menu"
                            role="listbox"
                            aria-label={`${label}: модели из каталога`}
                        >
                        {filteredCatalog.map((entry, index) => {
                            const conflict =
                                conflictingKeys.has(
                                    entry.modelKey.toLowerCase(),
                                )

                            return (
                                <button
                                    id={`${listboxId}-option-${index}`}
                                    key={entry.modelKey}
                                    type="button"
                                    role="option"
                                    aria-label={[
                                        `${entry.displayName} ${entry.modelKey}`,
                                        `${entry.provider} / ${entry.providerModelId}`,
                                        modelExecutionLabel(
                                            entry.modelKey,
                                            catalog,
                                            effectiveCatalog,
                                            runtime,
                                        ),
                                        conflict
                                            ? `уже в списке ${sourceLabel}`
                                            : '',
                                    ].filter(Boolean).join(' · ')}
                                    aria-selected={activeIndex === index}
                                    disabled={disabled || conflict}
                                    className={[
                                        'models-model-selector__option',
                                        activeIndex === index
                                            ? 'models-model-selector__option--active'
                                            : '',
                                    ].filter(Boolean).join(' ')}
                                    onMouseDown={(event) => {
                                        event.preventDefault()
                                    }}
                                    onMouseMove={() => {
                                        setActiveIndex(index)
                                    }}
                                    onClick={() => {
                                        if (conflict) {
                                            setError(
                                                conflictMessage(kind),
                                            )
                                            return
                                        }
                                        commitKey(entry.modelKey)
                                    }}
                                >
                                    <span>
                                        <strong>
                                            {entry.displayName}
                                        </strong>
                                        <code>
                                            {entry.modelKey}
                                        </code>
                                    </span>
                                    <small>
                                        {entry.provider}
                                        {' / '}
                                        {entry.providerModelId}
                                        {' · '}
                                        {modelExecutionLabel(
                                            entry.modelKey,
                                            catalog,
                                            effectiveCatalog,
                                            runtime,
                                        )}
                                        {conflict
                                            ? ` · уже в списке ${sourceLabel}`
                                            : ''}
                                    </small>
                                </button>
                            )
                        })}

                        {manualCandidate && (
                            <button
                                id={`${listboxId}-option-${filteredCatalog.length}`}
                                type="button"
                                role="option"
                                aria-selected={activeIndex === filteredCatalog.length}
                                disabled={disabled}
                                className={[
                                    'models-model-selector__option',
                                    'models-model-selector__option--manual',
                                    activeIndex
                                        === filteredCatalog.length
                                        ? 'models-model-selector__option--active'
                                        : '',
                                ].filter(Boolean).join(' ')}
                                onMouseDown={(event) => {
                                    event.preventDefault()
                                }}
                                onMouseMove={() => {
                                    setActiveIndex(filteredCatalog.length)
                                }}
                                onClick={() => {
                                    commitKey(manualCandidate)
                                }}
                            >
                                <span>
                                    <strong>
                                        Добавить вручную
                                    </strong>
                                    <code>
                                        {manualCandidate}
                                    </code>
                                </span>
                                <small>
                                    Корректный ключ, но модели сейчас нет в каталоге
                                </small>
                            </button>
                        )}

                        {filteredCatalog.length === 0
                            && !manualCandidate
                            && (
                                <div className="models-model-selector__empty">
                                    {query.trim()
                                        ? 'Совпадений в каталоге нет. Для ручного добавления введите полный ключ и нажмите Enter.'
                                        : 'Начните вводить название, провайдера или ключ модели.'}
                                </div>
                            )}
                        </div>
                        {onTransferFromOppositeList && conflictingCatalogEntries.length > 0 && (
                            <div className="models-model-selector__conflicts">
                                {conflictingCatalogEntries.map(entry => (
                                    <div key={entry.modelKey} className="models-model-selector__conflict-row">
                                        <span>
                                            <strong>{entry.displayName}</strong> уже в списке {sourceLabel}.
                                        </span>
                                        <button
                                            type="button"
                                            disabled={disabled || selectedKeys.length >= MAX_POLICY_MODEL_KEYS}
                                            aria-label={`Перенести ${entry.modelKey} в ${destinationLabel}`}
                                            onClick={() => setPendingTransferKey(entry.modelKey.toLowerCase())}
                                        >
                                            Перенести в {destinationLabel}
                                        </button>
                                    </div>
                                ))}
                            </div>
                        )}
                        {selectedKeys.length >= MAX_POLICY_MODEL_KEYS && conflictingCatalogEntries.length > 0 && (
                            <p className="models-model-selector__scroll-hint" role="status">
                                Список {destinationLabel} заполнен (200 моделей). Для переноса освободите место.
                            </p>
                        )}
                        {transferEntry && onTransferFromOppositeList && (
                            <div className="models-model-selector__transfer-confirm" role="group"
                                 aria-label={`Подтверждение переноса ${transferEntry.modelKey}`}>
                                <strong>Перенести «{transferEntry.displayName}» в {destinationLabel}?</strong>
                                <p>Модель будет удалена из списка {sourceLabel} и добавлена в {destinationLabel} одним изменением черновика.</p>
                                {kind === 'deny' && sourceWillBecomeEmpty && (
                                    <p className="models-model-selector__transfer-risk">
                                        Важно: список разрешённых станет пустым. Это означает, что другие модели
                                        не будут ограничены этим списком; останутся только явные запреты и остальные правила.
                                    </p>
                                )}
                                {kind === 'deny' && (
                                    <p>Если модель закреплена по умолчанию, этот выбор будет сброшен.</p>
                                )}
                                <div className="models-model-selector__transfer-actions">
                                    <button type="button" disabled={disabled} onClick={() => {
                                        onTransferFromOppositeList(transferEntry.modelKey)
                                        setPendingTransferKey(null)
                                        setExpanded(false)
                                        setQuery('')
                                        setError('')
                                        setActiveIndex(0)
                                    }}>Подтвердить перенос</button>
                                    <button type="button" onClick={() => setPendingTransferKey(null)}>Отмена</button>
                                </div>
                            </div>
                        )}
                    </div>
                )}
            </div>

            {error ? (
                <p
                    className="models-model-selector__error"
                    role="alert"
                >
                    {error}
                </p>
            ) : (
                <p className="models-model-selector__hint">
                    {hint}
                    {' '}
                    Регистр не важен: ключ сохраняется в нижнем регистре.
                    Можно вставить несколько ключей через перенос строки,
                    запятую или точку с запятой.
                </p>
            )}
        </div>
    )
}

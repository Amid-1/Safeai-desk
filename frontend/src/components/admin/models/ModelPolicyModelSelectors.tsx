import {
    useEffect,
    useId,
    useMemo,
    useRef,
    useState,
} from 'react'
import type {
    RefObject,
} from 'react'
import type {
    ModelCatalogEntry,
} from '../../../api/modelApi'
import {
    normalizeModelKey,
} from './modelControlPlaneSupport'

type SelectorKind =
    | 'allow'
    | 'deny'

export type ModelKeySelectorInteractionState = {
    hasPendingInput: boolean
    hasError: boolean
}

type ModelKeySelectorProps = {
    label: string
    hint: string
    kind: SelectorKind
    catalog: ModelCatalogEntry[]
    value: string
    conflictingValue: string
    disabled?: boolean
    inputRef?: RefObject<HTMLInputElement | null>
    onChange: (value: string) => void
    onInteractionStateChange?: (
        state: ModelKeySelectorInteractionState,
    ) => void
}

type DefaultModelSelectorProps = {
    catalog: ModelCatalogEntry[]
    allowModelKeys: string
    denyModelKeys: string
    value: string
    disabled?: boolean
    onChange: (value: string) => void
}

type NormalizedModelKey =
    | {
        valid: true
        value: string
    }
    | {
        valid: false
        message: string
    }

const MAX_POLICY_MODEL_KEYS = 200
const MAX_VISIBLE_OPTIONS = 8

function splitDraftModelKeys(
    value: string,
): string[] {
    if (!value.trim()) {
        return []
    }

    return value
        .split(/[\s,;]+/)
        .map((item) => item.trim())
        .filter(Boolean)
        .map((item) => item.toLowerCase())
}

function uniqueModelKeys(
    values: string[],
): string[] {
    return Array.from(
        new Set(values),
    )
}

function validateModelKey(
    value: string,
): NormalizedModelKey {
    try {
        return {
            valid: true,
            value: normalizeModelKey(value),
        }
    } catch (failure) {
        return {
            valid: false,
            message:
                failure instanceof Error
                    ? failure.message
                    : 'Некорректный ключ модели.',
        }
    }
}

function catalogSearchText(
    entry: ModelCatalogEntry,
): string {
    return [
        entry.displayName,
        entry.modelKey,
        entry.provider,
        entry.providerModelId,
    ]
        .join(' ')
        .toLowerCase()
}

function catalogByKey(
    catalog: ModelCatalogEntry[],
): Map<string, ModelCatalogEntry> {
    return new Map(
        catalog.map((entry) => [
            entry.modelKey.toLowerCase(),
            entry,
        ]),
    )
}

function conflictMessage(
    kind: SelectorKind,
): string {
    return kind === 'allow'
        ? 'Эта модель уже находится в списке запрещённых.'
        : 'Эта модель уже находится в списке разрешённых.'
}

function selectorDescription(
    kind: SelectorKind,
): string {
    return kind === 'allow'
        ? 'Если список заполнен, организация сможет использовать только выбранные модели.'
        : 'Выбранные модели будут запрещены для организации.'
}

export function ModelKeySelector({
    label,
    hint,
    kind,
    catalog,
    value,
    conflictingValue,
    disabled = false,
    inputRef,
    onChange,
    onInteractionStateChange,
}: ModelKeySelectorProps) {
    const inputId = useId()
    const listboxId = useId()
    const controlRef =
        useRef<HTMLDivElement | null>(null)
    const [query, setQuery] =
        useState('')
    const [error, setError] =
        useState('')
    const [expanded, setExpanded] =
        useState(false)
    const [activeIndex, setActiveIndex] =
        useState(0)

    useEffect(() => {
        onInteractionStateChange?.({
            hasPendingInput:
                Boolean(query.trim()),
            hasError: Boolean(error),
        })
    }, [
        error,
        onInteractionStateChange,
        query,
    ])

    const selectedKeys = useMemo(
        () => uniqueModelKeys(
            splitDraftModelKeys(value),
        ),
        [value],
    )

    const conflictingKeys = useMemo(
        () => new Set(
            splitDraftModelKeys(
                conflictingValue,
            ),
        ),
        [conflictingValue],
    )

    const catalogLookup = useMemo(
        () => catalogByKey(catalog),
        [catalog],
    )

    const normalizedQuery =
        query.trim().toLowerCase()

    const filteredCatalog = useMemo(() => {
        const selected =
            new Set(selectedKeys)

        return catalog
            .filter((entry) =>
                !selected.has(
                    entry.modelKey.toLowerCase(),
                ),
            )
            .filter((entry) =>
                !normalizedQuery
                || catalogSearchText(entry)
                    .includes(normalizedQuery),
            )
            .slice(0, MAX_VISIBLE_OPTIONS)
    }, [
        catalog,
        normalizedQuery,
        selectedKeys,
    ])

    const manualCandidate = useMemo(() => {
        if (!query.trim()) {
            return null
        }

        const result = validateModelKey(query)
        if (!result.valid) {
            return null
        }

        if (
            catalogLookup.has(result.value)
            || selectedKeys.includes(
                result.value,
            )
        ) {
            return null
        }

        return result.value
    }, [
        catalogLookup,
        query,
        selectedKeys,
    ])

    const commitKey = (
        rawValue: string,
    ): boolean => {
        const result =
            validateModelKey(rawValue)

        if (!result.valid) {
            setError(result.message)
            return false
        }

        const key = result.value

        if (conflictingKeys.has(key)) {
            setError(
                conflictMessage(kind),
            )
            return false
        }

        if (selectedKeys.includes(key)) {
            setQuery('')
            setError('')
            return true
        }

        if (
            selectedKeys.length
                >= MAX_POLICY_MODEL_KEYS
        ) {
            setError(
                'Можно указать не более 200 моделей.',
            )
            return false
        }

        onChange(
            [...selectedKeys, key]
                .join('\n'),
        )
        setQuery('')
        setError('')
        setExpanded(false)
        setActiveIndex(0)
        return true
    }

    const commitMany = (
        rawValue: string,
    ) => {
        const parts = rawValue
            .split(/[\n\r,;]+/)
            .map((item) => item.trim())
            .filter(Boolean)

        if (parts.length === 0) {
            return
        }

        const normalized: string[] = []
        for (const part of parts) {
            const result =
                validateModelKey(part)

            if (!result.valid) {
                setError(
                    `Не удалось добавить «${part}»: ${result.message}`,
                )
                return
            }

            if (
                conflictingKeys.has(
                    result.value,
                )
            ) {
                setError(
                    `Не удалось добавить «${part}»: ${conflictMessage(kind)}`,
                )
                return
            }

            normalized.push(result.value)
        }

        const next = uniqueModelKeys([
            ...selectedKeys,
            ...normalized,
        ])

        if (
            next.length
                > MAX_POLICY_MODEL_KEYS
        ) {
            setError(
                'Можно указать не более 200 моделей.',
            )
            return
        }

        onChange(next.join('\n'))
        setQuery('')
        setError('')
        setExpanded(false)
        setActiveIndex(0)
    }

    const removeKey = (
        key: string,
    ) => {
        onChange(
            selectedKeys
                .filter((item) =>
                    item !== key,
                )
                .join('\n'),
        )
        setError('')
    }

    const optionCount =
        filteredCatalog.length
        + (manualCandidate ? 1 : 0)

    useEffect(() => {
        if (!expanded) {
            return
        }

        const handlePointerDown = (
            event: PointerEvent,
        ) => {
            const target = event.target

            if (
                target instanceof Node
                && controlRef.current
                    ?.contains(target)
            ) {
                return
            }

            setExpanded(false)
        }

        document.addEventListener(
            'pointerdown',
            handlePointerDown,
        )

        return () => {
            document.removeEventListener(
                'pointerdown',
                handlePointerDown,
            )
        }
    }, [expanded])

    useEffect(() => {
        if (optionCount <= 0) {
            setActiveIndex(0)
            return
        }

        setActiveIndex((current) =>
            Math.min(
                current,
                optionCount - 1,
            ),
        )
    }, [optionCount])

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
                    {selectedKeys.length} / {MAX_POLICY_MODEL_KEYS}
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
                {selectedKeys.length > 0 && (
                    <div
                        className="models-model-selector__chips"
                        aria-label={`${label}: выбранные модели`}
                    >
                        {selectedKeys.map((key) => {
                            const entry =
                                catalogLookup.get(key)
                            const unknown = !entry

                            return (
                                <span
                                    key={key}
                                    className={[
                                        'models-model-chip',
                                        unknown
                                            ? 'models-model-chip--warning'
                                            : '',
                                    ].filter(Boolean).join(' ')}
                                    title={
                                        unknown
                                            ? 'Ключ корректен, но такой модели сейчас нет в каталоге. Правило можно сохранить для будущей модели.'
                                            : `${entry.displayName} · ${entry.provider}/${entry.providerModelId}`
                                    }
                                >
                                    {entry && (
                                        <strong>
                                            {entry.displayName}
                                        </strong>
                                    )}
                                    <code>{key}</code>
                                    {unknown && (
                                        <span className="models-model-chip__warning">
                                            нет в каталоге
                                        </span>
                                    )}
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
                    </div>
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
                        aria-invalid={Boolean(error)}
                        placeholder="Найти в каталоге или ввести ключ модели"
                        onFocus={() => {
                            setExpanded(true)
                        }}
                        onChange={(event) => {
                            setQuery(
                                event.target.value,
                            )
                            setError('')
                            setExpanded(true)
                            setActiveIndex(0)
                        }}
                        onPaste={(event) => {
                            const pasted =
                                event.clipboardData
                                    .getData('text')

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
                                    Math.max(
                                        current - 1,
                                        0,
                                    ),
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

                            const exactQuery =
                                validateModelKey(query)

                            if (
                                query.trim()
                                && exactQuery.valid
                                && (
                                    catalogLookup.has(
                                        exactQuery.value,
                                    )
                                    || selectedKeys.includes(
                                        exactQuery.value,
                                    )
                                )
                            ) {
                                commitKey(query)
                                return
                            }

                            const activeCatalogEntry =
                                filteredCatalog[
                                    activeIndex
                                ]

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
                                    key={entry.modelKey}
                                    type="button"
                                    role="option"
                                    aria-selected={false}
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
                                        {' '}
                                        <code>
                                            {entry.modelKey}
                                        </code>
                                    </span>
                                    <small>
                                        {entry.provider}
                                        {' / '}
                                        {entry.providerModelId}
                                        {conflict
                                            ? ' · уже в противоположном списке'
                                            : ''}
                                    </small>
                                </button>
                            )
                        })}

                        {manualCandidate && (
                            <button
                                type="button"
                                role="option"
                                aria-selected={false}
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
                                        ? 'Совпадений в каталоге нет. Для ручного добавления введите полный ключ, например openai:gpt-5, и нажмите Enter.'
                                        : 'Начните вводить название, провайдера или ключ модели.'}
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
                    {hint} Регистр не важен: ключ автоматически сохраняется в нижнем регистре. Можно вставить несколько ключей через перенос строки, запятую или точку с запятой.
                </p>
            )}
        </div>
    )
}

export function DefaultModelSelector({
    catalog,
    allowModelKeys,
    denyModelKeys,
    value,
    disabled = false,
    onChange,
}: DefaultModelSelectorProps) {
    const containerRef =
        useRef<HTMLDivElement | null>(null)
    const searchRef =
        useRef<HTMLInputElement | null>(null)
    const listboxId = useId()
    const [expanded, setExpanded] =
        useState(false)
    const [query, setQuery] =
        useState('')

    const allowKeys = useMemo(
        () => new Set(
            splitDraftModelKeys(
                allowModelKeys,
            ),
        ),
        [allowModelKeys],
    )
    const denyKeys = useMemo(
        () => new Set(
            splitDraftModelKeys(
                denyModelKeys,
            ),
        ),
        [denyModelKeys],
    )
    const lookup = useMemo(
        () => catalogByKey(catalog),
        [catalog],
    )

    const selectableKeys = useMemo(() => {
        const result = new Set<string>()

        for (const entry of catalog) {
            const key =
                entry.modelKey.toLowerCase()

            if (denyKeys.has(key)) {
                continue
            }

            if (
                allowKeys.size > 0
                && !allowKeys.has(key)
            ) {
                continue
            }

            result.add(key)
        }

        for (const key of allowKeys) {
            if (!denyKeys.has(key)) {
                result.add(key)
            }
        }

        if (
            value
            && !denyKeys.has(
                value.toLowerCase(),
            )
        ) {
            result.add(value.toLowerCase())
        }

        return Array.from(result)
    }, [
        allowKeys,
        catalog,
        denyKeys,
        value,
    ])

    const filteredKeys = useMemo(() => {
        const normalized =
            query.trim().toLowerCase()

        return selectableKeys
            .filter((key) => {
                if (!normalized) {
                    return true
                }

                const entry = lookup.get(key)
                const haystack = entry
                    ? catalogSearchText(entry)
                    : key

                return haystack.includes(normalized)
            })
            .slice(0, MAX_VISIBLE_OPTIONS)
    }, [
        lookup,
        query,
        selectableKeys,
    ])

    useEffect(() => {
        if (!expanded) {
            return
        }

        const handlePointerDown = (
            event: PointerEvent,
        ) => {
            const target =
                event.target

            if (
                !(target instanceof Node)
                || containerRef.current
                    ?.contains(target)
            ) {
                return
            }

            setExpanded(false)
        }

        document.addEventListener(
            'pointerdown',
            handlePointerDown,
        )

        return () => {
            document.removeEventListener(
                'pointerdown',
                handlePointerDown,
            )
        }
    }, [expanded])

    useEffect(() => {
        if (expanded) {
            searchRef.current?.focus()
        }
    }, [expanded])

    const selectedKey =
        value.trim().toLowerCase()
    const selectedEntry =
        selectedKey
            ? lookup.get(selectedKey)
            : undefined
    const selectionError =
        selectedKey && denyKeys.has(selectedKey)
            ? 'Модель по умолчанию находится в списке запрещённых. Выберите другую модель.'
            : selectedKey
                && allowKeys.size > 0
                && !allowKeys.has(selectedKey)
                ? 'При заполненном списке разрешённых модель по умолчанию должна входить в него.'
                : ''

    return (
        <div
            ref={containerRef}
            className="models-default-selector"
        >
            <button
                type="button"
                className="models-default-selector__button"
                disabled={disabled}
                aria-haspopup="listbox"
                aria-expanded={expanded}
                aria-controls={listboxId}
                onClick={() => {
                    setExpanded((current) =>
                        !current,
                    )
                    setQuery('')
                }}
            >
                <span>
                    <strong>
                        {selectedKey
                            ? selectedEntry?.displayName
                                ?? selectedKey
                            : 'Использовать подключённую модель'}
                    </strong>
                    <small>
                        {selectedKey
                            ? selectedEntry
                                ? selectedEntry.modelKey
                                : 'Ключ пока отсутствует в текущем каталоге'
                            : 'Автоматически использовать фактически подключённую модель'}
                    </small>
                </span>
                <span
                    className="models-default-selector__chevron"
                    aria-hidden="true"
                >
                    ▾
                </span>
            </button>

            {expanded && (
                <div className="models-default-selector__popover">
                    <div className="models-default-selector__search">
                        <span aria-hidden="true">⌕</span>
                        <input
                            ref={searchRef}
                            type="text"
                            value={query}
                            autoComplete="off"
                            placeholder="Найти модель"
                            onChange={(event) => {
                                setQuery(
                                    event.target.value,
                                )
                            }}
                            onKeyDown={(event) => {
                                if (event.key === 'Escape') {
                                    setExpanded(false)
                                }
                            }}
                        />
                    </div>

                    <div
                        id={listboxId}
                        role="listbox"
                        className="models-default-selector__options"
                    >
                        <button
                            type="button"
                            role="option"
                            aria-selected={!selectedKey}
                            className="models-default-selector__option"
                            onClick={() => {
                                onChange('')
                                setExpanded(false)
                            }}
                        >
                            <span>
                                <strong>
                                    Подключённая модель
                                </strong>
                                <small>
                                    Использовать фактическое подключение сервера
                                </small>
                            </span>
                        </button>

                        {filteredKeys.map((key) => {
                            const entry = lookup.get(key)

                            return (
                                <button
                                    key={key}
                                    type="button"
                                    role="option"
                                    aria-selected={
                                        selectedKey === key
                                    }
                                    className="models-default-selector__option"
                                    onClick={() => {
                                        onChange(key)
                                        setExpanded(false)
                                    }}
                                >
                                    <span>
                                        <strong>
                                            {entry?.displayName
                                                ?? key}
                                        </strong>
                                        {' '}
                                        <code>{key}</code>
                                    </span>
                                    <small>
                                        {entry
                                            ? `${entry.provider} / ${entry.providerModelId}`
                                            : 'Ключ из списка разрешённых; модели пока нет в каталоге'}
                                    </small>
                                </button>
                            )
                        })}

                        {filteredKeys.length === 0 && (
                            <div className="models-default-selector__empty">
                                Подходящих моделей нет. Проверьте список разрешённых и запрещённых моделей.
                            </div>
                        )}
                    </div>
                </div>
            )}

            {selectionError && (
                <p
                    className="models-default-selector__error"
                    role="alert"
                >
                    {selectionError}
                </p>
            )}
        </div>
    )
}

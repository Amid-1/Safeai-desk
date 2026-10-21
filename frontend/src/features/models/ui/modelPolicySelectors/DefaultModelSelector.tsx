// frontend/src/features/models/ui/modelPolicySelectors/DefaultModelSelector.tsx
// Назначение: выбор модели по умолчанию или режима Runtime.

import {useEffect, useId, useMemo, useRef, useState} from 'react'
import type {DefaultModelSelectorProps} from './selectorTypes'
import {
    MAX_VISIBLE_OPTIONS,
    splitDraftModelKeys,
    catalogByKey,
    catalogSearchText,
    modelExecutionLabel,
    runtimeExecutionLabel,
} from './selectorSupport'

export function DefaultModelSelector({
    catalog,
    effectiveCatalog,
    runtime,
    allowModelKeys,
    denyModelKeys,
    value,
    disabled = false,
    onChange,
}: DefaultModelSelectorProps) {
    const containerRef =
        useRef<HTMLDivElement | null>(null)
    const triggerRef =
        useRef<HTMLButtonElement | null>(null)
    const searchRef =
        useRef<HTMLInputElement | null>(null)
    const listboxId = useId()
    const [expanded, setExpanded] =
        useState(false)
    const [query, setQuery] =
        useState('')
    const [activeIndex, setActiveIndex] =
        useState(0)

    const allowKeys = useMemo(
        () => new Set(
            splitDraftModelKeys(allowModelKeys),
        ),
        [allowModelKeys],
    )
    const denyKeys = useMemo(
        () => new Set(
            splitDraftModelKeys(denyModelKeys),
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
            const key = entry.modelKey.toLowerCase()

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
            && !denyKeys.has(value.toLowerCase())
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
        const normalized = query.trim().toLowerCase()

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

    const defaultOptionCount = 1 + filteredKeys.length
    const activeDefaultOptionId = expanded
        ? `${listboxId}-option-${activeIndex}`
        : undefined

    const chooseDefaultOption = (index: number) => {
        if (index === 0) {
            onChange('')
            setExpanded(false)
            return
        }

        const key = filteredKeys[index - 1]
        if (!key) {
            return
        }

        onChange(key)
        setExpanded(false)
    }

    useEffect(() => {
        if (!expanded) {
            return
        }

        const handlePointerDown = (
            event: PointerEvent,
        ) => {
            const target = event.target

            if (
                !(target instanceof Node)
                || containerRef.current?.contains(target)
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

    useEffect(() => {
        setActiveIndex((current) =>
            Math.min(current, Math.max(defaultOptionCount - 1, 0)),
        )
    }, [defaultOptionCount])

    const selectedKey = value.trim().toLowerCase()
    const selectedEntry = selectedKey
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
                ref={triggerRef}
                type="button"
                className="models-default-selector__button"
                disabled={disabled}
                aria-haspopup="listbox"
                aria-label={selectedKey
                    ? undefined
                    : 'Использовать подключённую модель — автоматически, текущий Runtime'}
                aria-expanded={expanded}
                aria-controls={listboxId}
                onClick={() => {
                    setExpanded((current) => !current)
                    setQuery('')
                    setActiveIndex(0)
                }}
            >
                <span>
                    <strong>
                        {selectedKey
                            ? `Закреплена — ${selectedEntry?.displayName ?? selectedKey}`
                            : 'Автоматически — текущий Runtime'}
                    </strong>
                    <small>
                        {selectedKey
                            ? `${selectedKey} · ${modelExecutionLabel(
                                selectedKey,
                                catalog,
                                effectiveCatalog,
                                runtime,
                            )}`
                            : runtimeExecutionLabel(
                                effectiveCatalog,
                                runtime,
                            )}
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
                            role="combobox"
                            aria-label="Найти модель по умолчанию"
                            aria-expanded={expanded}
                            aria-controls={listboxId}
                            aria-autocomplete="list"
                            aria-activedescendant={activeDefaultOptionId}
                            placeholder="Найти модель"
                            onChange={(event) => {
                                setQuery(event.target.value)
                                setActiveIndex(0)
                            }}
                            onKeyDown={(event) => {
                                if (event.key === 'ArrowDown') {
                                    event.preventDefault()
                                    setActiveIndex((current) =>
                                        Math.min(
                                            current + 1,
                                            defaultOptionCount - 1,
                                        ),
                                    )
                                    return
                                }

                                if (event.key === 'ArrowUp') {
                                    event.preventDefault()
                                    setActiveIndex((current) =>
                                        Math.max(current - 1, 0),
                                    )
                                    return
                                }

                                if (event.key === 'Enter') {
                                    event.preventDefault()
                                    chooseDefaultOption(activeIndex)
                                    return
                                }

                                if (event.key === 'Escape') {
                                    event.preventDefault()
                                    setExpanded(false)
                                    triggerRef.current?.focus()
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
                            id={`${listboxId}-option-0`}
                            type="button"
                            role="option"
                            aria-label="Использовать подключённую модель — автоматически, текущий Runtime"
                            aria-selected={!selectedKey}
                            className={[
                                'models-default-selector__option',
                                activeIndex === 0
                                    ? 'models-default-selector__option--active'
                                    : '',
                            ].filter(Boolean).join(' ')}
                            onMouseMove={() => {
                                setActiveIndex(0)
                            }}
                            onClick={() => {
                                chooseDefaultOption(0)
                            }}
                        >
                            <span>
                                <strong>
                                    Автоматически — текущий Runtime
                                </strong>
                                <small>
                                    {runtimeExecutionLabel(
                                        effectiveCatalog,
                                        runtime,
                                    )}
                                </small>
                            </span>
                        </button>

                        {filteredKeys.map((key, index) => {
                            const entry = lookup.get(key)
                            const optionIndex = index + 1

                            return (
                                <button
                                    id={`${listboxId}-option-${optionIndex}`}
                                    key={key}
                                    type="button"
                                    role="option"
                                    aria-label={entry
                                        ? `${entry.displayName} ${key} · ${entry.provider} / ${entry.providerModelId} · ${modelExecutionLabel(
                                            key,
                                            catalog,
                                            effectiveCatalog,
                                            runtime,
                                        )}`
                                        : `${key} · Ключ из allowlist; модели пока нет в каталоге`}
                                    aria-selected={selectedKey === key}
                                    className={[
                                        'models-default-selector__option',
                                        activeIndex === optionIndex
                                            ? 'models-default-selector__option--active'
                                            : '',
                                    ].filter(Boolean).join(' ')}
                                    onMouseMove={() => {
                                        setActiveIndex(optionIndex)
                                    }}
                                    onClick={() => {
                                        chooseDefaultOption(optionIndex)
                                    }}
                                >
                                    <span>
                                        <strong>
                                            {entry?.displayName ?? key}
                                        </strong>
                                        <code>{key}</code>
                                    </span>
                                    <small>
                                        {entry
                                            ? `${entry.provider} / ${entry.providerModelId} · ${modelExecutionLabel(
                                                key,
                                                catalog,
                                                effectiveCatalog,
                                                runtime,
                                            )}`
                                            : 'Ключ из allowlist; модели пока нет в каталоге'}
                                    </small>
                                </button>
                            )
                        })}

                        {filteredKeys.length === 0 && (
                            <div className="models-default-selector__empty">
                                Подходящих моделей нет. Проверьте списки разрешённых и запрещённых моделей.
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

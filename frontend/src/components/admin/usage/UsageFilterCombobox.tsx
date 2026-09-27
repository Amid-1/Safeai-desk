// frontend/src/components/admin/usage/UsageFilterCombobox.tsx

import {
    useEffect,
    useId,
    useMemo,
    useRef,
    useState,
} from 'react'
import type {
    ChangeEvent,
    KeyboardEvent,
    MouseEvent,
} from 'react'
import './UsageFilterCombobox.css'

export type UsageFilterOption = {
    value: string
    label: string
    description?: string
    status?: string
    statusTone?:
        | 'positive'
        | 'muted'
        | 'warning'
}

type UsageFilterComboboxProps = {
    id: string
    label: string
    value: string
    options: UsageFilterOption[]
    placeholder: string
    searchPlaceholder: string
    emptyMessage: string

    selectedLabel?: string
    helperText?: string
    loading?: boolean
    error?: string
    disabled?: boolean
    filterLocally?: boolean

    /**
     * Hard upper bound for the search query sent to local/server filtering.
     * Optional for reusable/tests; production callers can set a stricter
     * domain-specific value (for example 100 for model and 255 for org).
     */
    maxQueryLength?: number

    onChange: (
        value: string,
        option: UsageFilterOption | null,
    ) => void

    onQueryChange?: (query: string) => void
}

const DEFAULT_MAX_QUERY_LENGTH = 255

export function UsageFilterCombobox({
    id,
    label,
    value,
    options,
    placeholder,
    searchPlaceholder,
    emptyMessage,
    selectedLabel,
    helperText,
    loading = false,
    error = '',
    disabled = false,
    filterLocally = true,
    maxQueryLength = DEFAULT_MAX_QUERY_LENGTH,
    onChange,
    onQueryChange,
}: UsageFilterComboboxProps) {
    const reactId = useId()
    const listboxId =
        `${id}-${reactId}-listbox`
    const helperId =
        `${id}-${reactId}-helper`

    const rootRef =
        useRef<HTMLDivElement | null>(null)
    const inputRef =
        useRef<HTMLInputElement | null>(null)

    const [expanded, setExpanded] =
        useState(false)
    const [query, setQuery] =
        useState('')
    const [activeIndex, setActiveIndex] =
        useState(0)

    const safeMaxQueryLength =
        Number.isSafeInteger(maxQueryLength)
        && maxQueryLength > 0
            ? maxQueryLength
            : DEFAULT_MAX_QUERY_LENGTH

    const selectedOption = useMemo(
        () => options.find(
            (option) =>
                option.value === value,
        ) ?? null,
        [options, value],
    )

    const visibleOptions = useMemo(
        () => {
            const normalizedQuery =
                normalizeSearchText(query)

            if (!normalizedQuery) {
                return options
            }

            if (!filterLocally) {
                /*
                 * Server results are authoritative and must not be filtered
                 * a second time in the browser. Only hide the synthetic
                 * "all" option while the user is actively searching so Enter
                 * selects a real server result rather than clearing scope.
                 */
                return options.filter(
                    (option) =>
                        option.value !== '',
                )
            }

            return options.filter(
                (option) => {
                    if (!option.value) {
                        return false
                    }

                    const searchable =
                        normalizeSearchText(
                            [
                                option.label,
                                option.value,
                                option.description
                                ?? '',
                                option.status
                                ?? '',
                            ].join(' '),
                        )

                    return searchable.includes(
                        normalizedQuery,
                    )
                },
            )
        },
        [
            filterLocally,
            options,
            query,
        ],
    )

    const displayValue =
        expanded
            ? query
            : selectedLabel
                ?? selectedOption?.label
                ?? (value || placeholder)

    useEffect(() => {
        if (!expanded) {
            return
        }

        function handlePointerDown(
            event: PointerEvent,
        ) {
            const target = event.target

            if (
                target instanceof Node
                && rootRef.current?.contains(
                    target,
                )
            ) {
                return
            }

            closeMenu()
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
        if (visibleOptions.length === 0) {
            setActiveIndex(0)
            return
        }

        setActiveIndex((current) =>
            Math.min(
                current,
                visibleOptions.length - 1,
            ),
        )
    }, [visibleOptions.length])

    function openMenu() {
        if (disabled) {
            return
        }

        setQuery('')
        setActiveIndex(0)
        setExpanded(true)
        onQueryChange?.('')
    }

    function closeMenu() {
        setExpanded(false)
        setQuery('')
        setActiveIndex(0)
    }

    function changeQuery(
        rawQuery: string,
    ) {
        const nextQuery =
            rawQuery.slice(
                0,
                safeMaxQueryLength,
            )

        setQuery(nextQuery)
        setExpanded(true)
        setActiveIndex(0)
        onQueryChange?.(nextQuery)
    }

    function selectOption(
        option: UsageFilterOption,
    ) {
        onChange(
            option.value,
            option,
        )
        closeMenu()
        inputRef.current?.focus()
    }

    function clearSelection() {
        const emptyOption =
            options.find(
                (option) =>
                    option.value === '',
            ) ?? null

        onChange('', emptyOption)
        closeMenu()
        inputRef.current?.focus()
    }

    return (
        <div
            ref={rootRef}
            className={[
                'usage-filter-combobox',
                expanded
                    ? 'usage-filter-combobox--open'
                    : '',
                disabled
                    ? 'usage-filter-combobox--disabled'
                    : '',
            ]
                .filter(Boolean)
                .join(' ')}
        >
            <label
                className="usage-filter-combobox__label"
                htmlFor={id}
            >
                {label}
            </label>

            <div className="usage-filter-combobox__control">
                <span
                    className="usage-filter-combobox__search-icon"
                    aria-hidden="true"
                >
                    ⌕
                </span>

                <input
                    id={id}
                    ref={inputRef}
                    type="text"
                    role="combobox"
                    autoComplete="off"
                    spellCheck={false}
                    disabled={disabled}
                    maxLength={safeMaxQueryLength}
                    value={displayValue}
                    aria-expanded={expanded}
                    aria-controls={listboxId}
                    aria-autocomplete="list"
                    aria-activedescendant={
                        expanded
                        && visibleOptions.length > 0
                            ? `${listboxId}-option-${activeIndex}`
                            : undefined
                    }
                    aria-invalid={Boolean(error)}
                    aria-describedby={
                        helperText || error
                            ? helperId
                            : undefined
                    }
                    placeholder={
                        expanded
                            ? searchPlaceholder
                            : placeholder
                    }
                    onFocus={openMenu}
                    onClick={() => {
                        if (!expanded) {
                            openMenu()
                        }
                    }}
                    onChange={(event: ChangeEvent<HTMLInputElement>) => {
                        changeQuery(
                            event.target.value,
                        )
                    }}
                    onKeyDown={(event: KeyboardEvent<HTMLInputElement>) => {
                        if (event.key === 'Escape') {
                            event.preventDefault()
                            closeMenu()
                            inputRef.current?.blur()
                            return
                        }

                        if (event.key === 'Tab') {
                            closeMenu()
                            return
                        }

                        if (event.key === 'ArrowDown') {
                            event.preventDefault()

                            if (!expanded) {
                                openMenu()
                                return
                            }

                            if (
                                visibleOptions.length > 0
                            ) {
                                setActiveIndex(
                                    (current) =>
                                        Math.min(
                                            current + 1,
                                            visibleOptions.length
                                            - 1,
                                        ),
                                )
                            }
                            return
                        }

                        if (event.key === 'ArrowUp') {
                            event.preventDefault()

                            if (
                                visibleOptions.length > 0
                            ) {
                                setActiveIndex(
                                    (current) =>
                                        Math.max(
                                            current - 1,
                                            0,
                                        ),
                                )
                            }
                            return
                        }

                        if (event.key !== 'Enter') {
                            return
                        }

                        event.preventDefault()

                        if (!expanded) {
                            openMenu()
                            return
                        }

                        const option =
                            visibleOptions[
                                activeIndex
                            ]

                        if (option) {
                            selectOption(option)
                        }
                    }}
                />

                {value && !disabled
                    ? (
                        <button
                            type="button"
                            className="usage-filter-combobox__clear"
                            aria-label={
                                `Сбросить фильтр «${label}»`
                            }
                            title="Сбросить фильтр"
                            onMouseDown={(event: MouseEvent<HTMLButtonElement>) => {
                                event.preventDefault()
                            }}
                            onClick={clearSelection}
                        >
                            ×
                        </button>
                    )
                    : (
                        <span
                            className="usage-filter-combobox__chevron"
                            aria-hidden="true"
                        >
                            ▾
                        </span>
                    )}
            </div>

            {expanded && (
                <div
                    id={listboxId}
                    className="usage-filter-combobox__menu"
                    role="listbox"
                    aria-label={`${label}: варианты`}
                >
                    {loading && (
                        <div
                            className="usage-filter-combobox__message"
                            role="status"
                        >
                            Загрузка вариантов…
                        </div>
                    )}

                    {!loading && error && (
                        <div
                            className="usage-filter-combobox__message usage-filter-combobox__message--error"
                            role="alert"
                        >
                            {error}
                        </div>
                    )}

                    {!loading
                        && !error
                        && visibleOptions.length === 0
                        && (
                            <div className="usage-filter-combobox__message">
                                {emptyMessage}
                            </div>
                        )}

                    {!loading
                        && !error
                        && visibleOptions.map(
                            (option, index) => (
                                <div
                                    id={
                                        `${listboxId}-option-${index}`
                                    }
                                    key={
                                        option.value
                                        || '__all__'
                                    }
                                    role="option"
                                    aria-selected={
                                        option.value
                                        === value
                                    }
                                    className={[
                                        'usage-filter-combobox__option',
                                        index
                                            === activeIndex
                                            ? 'usage-filter-combobox__option--active'
                                            : '',
                                        option.value
                                            === value
                                            ? 'usage-filter-combobox__option--selected'
                                            : '',
                                    ]
                                        .filter(Boolean)
                                        .join(' ')}
                                    onMouseEnter={() => {
                                        setActiveIndex(
                                            index,
                                        )
                                    }}
                                    onMouseDown={(event: MouseEvent<HTMLDivElement>) => {
                                        event.preventDefault()
                                    }}
                                    onClick={() => {
                                        selectOption(
                                            option,
                                        )
                                    }}
                                >
                                    <div className="usage-filter-combobox__option-copy">
                                        <strong>
                                            {option.label}
                                        </strong>

                                        {option.description
                                            && (
                                                <span>
                                                    {
                                                        option.description
                                                    }
                                                </span>
                                            )}
                                    </div>

                                    {option.status && (
                                        <span
                                            className={[
                                                'usage-filter-combobox__status',
                                                option.statusTone
                                                    ? `usage-filter-combobox__status--${option.statusTone}`
                                                    : '',
                                            ]
                                                .filter(Boolean)
                                                .join(' ')}
                                        >
                                            {option.status}
                                        </span>
                                    )}

                                    {option.value
                                        === value
                                        && (
                                            <span
                                                className="usage-filter-combobox__check"
                                                aria-hidden="true"
                                            >
                                                ✓
                                            </span>
                                        )}
                                </div>
                            ),
                        )}
                </div>
            )}

            {(helperText || error) && (
                <small
                    id={helperId}
                    className={
                        error
                            ? 'usage-filter-combobox__helper usage-filter-combobox__helper--error'
                            : 'usage-filter-combobox__helper'
                    }
                >
                    {error || helperText}
                </small>
            )}
        </div>
    )
}

function normalizeSearchText(
    value: string,
): string {
    return value
        .trim()
        .toLocaleLowerCase('ru-RU')
}

// frontend/src/shared/ui/SearchableSelect.tsx

import {
    useEffect,
    useId,
    useLayoutEffect,
    useMemo,
    useRef,
    useState,
} from 'react'
import { createPortal } from 'react-dom'
import type {
    ChangeEvent,
    KeyboardEvent,
    MouseEvent,
} from 'react'
import './SearchableSelect.css'

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
    allowClear?: boolean
    filterLocally?: boolean
    /** Выпадающий список поверх прокручиваемой панели, без изменения её высоты. */
    overlay?: boolean

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

export function SearchableSelect({
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
    allowClear = true,
    filterLocally = true,
    overlay = false,
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
    const controlRef = useRef<HTMLDivElement>(null)
    const menuRef = useRef<HTMLDivElement>(null)

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
                && (rootRef.current?.contains(target) || menuRef.current?.contains(target))
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

    useLayoutEffect(() => {
        if (!overlay || !expanded || disabled) return
        const control = controlRef.current
        const menu = menuRef.current
        if (!control || !menu) return
        const clippingParents: HTMLElement[] = []
        for (let parent = control.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
            if (/(auto|scroll|hidden|clip)/.test(getComputedStyle(parent).overflowY)) clippingParents.push(parent)
        }
        const edge = 8
        const gap = 5
        const maximum = 290
        function reposition() {
            if (!control || !menu) return
            const rect = control.getBoundingClientRect()
            const viewportWidth = document.documentElement.clientWidth || window.innerWidth
            const viewportHeight = window.innerHeight
            const bounds = clippingParents.map(parent => parent.getBoundingClientRect())
            const visibleTop = Math.max(edge, ...bounds.map(parent => parent.top))
            const visibleBottom = Math.min(viewportHeight - edge, ...bounds.map(parent => parent.bottom))
            const below = Math.max(0, viewportHeight - rect.bottom - gap - edge)
            const above = Math.max(0, rect.top - gap - edge)
            const flip = below < maximum && above > below
            const width = Math.max(0, Math.min(rect.width, viewportWidth - 2 * edge))
            Object.assign(menu.style, {
                width: `${width}px`,
                left: `${Math.max(edge, Math.min(rect.left, viewportWidth - edge - width))}px`,
                top: flip ? 'auto' : `${rect.bottom + gap}px`,
                bottom: flip ? `${viewportHeight - rect.top + gap}px` : 'auto',
                maxHeight: `${Math.min(maximum, flip ? above : below)}px`,
                visibility: width === 0 || rect.bottom <= visibleTop || rect.top >= visibleBottom
                    ? 'hidden' : 'visible',
            })
        }
        reposition()
        window.addEventListener('resize', reposition)
        window.addEventListener('scroll', reposition, true)
        const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(reposition)
        observer?.observe(control)
        clippingParents.forEach(parent => observer?.observe(parent))
        return () => {
            window.removeEventListener('resize', reposition)
            window.removeEventListener('scroll', reposition, true)
            observer?.disconnect()
        }
    }, [overlay, expanded, disabled])

    useLayoutEffect(() => {
        if (!overlay || !expanded || loading || error) return
        const menu = menuRef.current
        const option = document.getElementById(`${listboxId}-option-${activeIndex}`)
        if (!menu || !option) return
        const bounds = menu.getBoundingClientRect()
        const item = option.getBoundingClientRect()
        if (item.bottom > bounds.bottom) menu.scrollTop += item.bottom - bounds.bottom
        else if (item.top < bounds.top) menu.scrollTop -= bounds.top - item.top
    }, [overlay, expanded, loading, error, listboxId, activeIndex, visibleOptions])

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
        if (loading || error) return
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

            <div className="usage-filter-combobox__control" ref={controlRef}>
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
                    aria-expanded={expanded && !disabled}
                    aria-controls={listboxId}
                    aria-autocomplete="list"
                    aria-activedescendant={
                        expanded && !disabled
                        && !loading && !error && visibleOptions.length > 0
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

                {allowClear && value && !disabled
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

            {expanded && !disabled && (() => {
                const menu = <div
                    id={listboxId}
                    ref={menuRef}
                    className={`usage-filter-combobox__menu${overlay ? ' usage-filter-combobox__menu--overlay' : ''}`}
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
                return overlay ? createPortal(menu, document.body) : menu
            })()}

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

// frontend/src/features/models/ui/modelPolicySelectors/useModelKeySelector.ts
// Состояние и бизнес-проверки селектора модели.

import {useEffect, useId, useMemo, useRef, useState} from 'react'
import type {ModelKeySelectorProps} from './selectorTypes'
import {
    MAX_POLICY_MODEL_KEYS,
    MAX_VISIBLE_OPTIONS,
    splitDraftModelKeys,
    uniqueModelKeys,
    validateModelKey,
    catalogSearchText,
    catalogByKey,
    conflictMessage,
} from './selectorSupport'

export function useModelKeySelector({
    kind,
    catalog,
    value,
    conflictingValue,
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
        const selected = new Set(selectedKeys)

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
            || selectedKeys.includes(result.value)
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
        const result = validateModelKey(rawValue)

        if (!result.valid) {
            setError(result.message)
            return false
        }

        const key = result.value

        if (conflictingKeys.has(key)) {
            setError(conflictMessage(kind))
            return false
        }

        if (selectedKeys.includes(key)) {
            setQuery('')
            setError('')
            return true
        }

        if (
            selectedKeys.length >= MAX_POLICY_MODEL_KEYS
        ) {
            setError(
                'Можно указать не более 200 моделей.',
            )
            return false
        }

        onChange(
            [...selectedKeys, key].join('\n'),
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
            const result = validateModelKey(part)

            if (!result.valid) {
                setError(
                    `Не удалось добавить «${part}»: ${result.message}`,
                )
                return
            }

            if (conflictingKeys.has(result.value)) {
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

        if (next.length > MAX_POLICY_MODEL_KEYS) {
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
                .filter((item) => item !== key)
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
                && controlRef.current?.contains(target)
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
            Math.min(current, optionCount - 1),
        )
    }, [optionCount])

    const activeOptionId =
        expanded && optionCount > 0
            ? `${listboxId}-option-${activeIndex}`
            : undefined

    return {
        controlRef, query, setQuery, error, setError,
        expanded, setExpanded, activeIndex, setActiveIndex,
        selectedKeys, conflictingKeys, catalogLookup,
        filteredCatalog, manualCandidate, commitKey, commitMany,
        removeKey, optionCount, activeOptionId, inputId, listboxId,
    }
}

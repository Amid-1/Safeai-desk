// Общий тестовый адаптер для React SetStateAction.
// Позволяет мокам корректно обрабатывать как значение, так и функциональное обновление.
import type { SetStateAction } from 'react'

export function applyReactStateUpdate<T>(
    previous: T,
    update: SetStateAction<T>,
): T {
    return typeof update === 'function'
        ? (update as (value: T) => T)(previous)
        : update
}

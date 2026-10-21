// frontend/src/components/modal/modalFocus.ts
// Ответственность модуля: начальный фокус и выбор доступных элементов.

import type { RefObject } from 'react'

const FOCUSABLE_SELECTOR = [
    'button:not([disabled])',
    'input:not([disabled])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    'a[href]',
    '[contenteditable="true"]',
    '[tabindex]:not([tabindex="-1"])',
].join(',')

export function resolveInitialFocus(
    card:
        HTMLElement,
    initialFocusRef:
        RefObject<HTMLElement | null>
        | undefined,
): HTMLElement {
    const requested =
        initialFocusRef?.current

    if (
        requested
        && card.contains(
            requested,
        )
        && isFocusable(
            requested,
        )
    ) {
        return requested
    }

    const focusable =
        getFocusableElements(
            card,
        )

    const nonCloseControl =
        focusable.find(
            (
                element,
            ) =>
                !element.hasAttribute(
                    'data-modal-close',
                ),
        )

    return nonCloseControl
        ?? focusable[0]
        ?? card
}

export function getFocusableElements(
    container:
        HTMLElement,
): HTMLElement[] {
    return Array.from(
        container.querySelectorAll<HTMLElement>(
            FOCUSABLE_SELECTOR,
        ),
    ).filter(
        isFocusable,
    )
}

function isFocusable(
    element:
        HTMLElement,
): boolean {
    if (
        element.hidden
        || element.getAttribute(
            'aria-hidden',
        ) === 'true'
        || element.closest(
            '[inert]',
        )
        || (
            'disabled'
                in element
            && Boolean(
                (
                    element as HTMLButtonElement
                ).disabled,
            )
        )
    ) {
        return false
    }

    const style =
        window.getComputedStyle(
            element,
        )

    return style.display
        !== 'none'
        && style.visibility
        !== 'hidden'
}

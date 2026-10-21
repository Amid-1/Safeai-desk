// frontend/src/shared/ui/modal/modalStack.ts
// Ответственность модуля: стек модальных окон, блокировка фона и возврат фокуса.

import { resolveInitialFocus } from './modalFocus'

type ModalEntry = {
    id: symbol
    card: HTMLElement
}

let modalEntries: ModalEntry[] = []
let modalStackVersion = 0

const modalStackListeners =
    new Set<() => void>()

let rootPreviouslyInert = false
let rootPreviousAriaHidden:
    string | null = null

let bodyPreviouslyLocked = false

export function registerModal(
    id:
        symbol,
    card:
        HTMLElement,
) {
    const wasEmpty =
        modalEntries.length
        === 0

    modalEntries = [
        ...modalEntries.filter(
            (
                entry,
            ) =>
                entry.id
                !== id,
        ),
        {
            id,
            card,
        },
    ]

    if (
        wasEmpty
    ) {
        lockApplicationBackground()
    }

    publishModalStackChange()
}

export function unregisterModal(
    id:
        symbol,
) {
    modalEntries =
        modalEntries.filter(
            (
                entry,
            ) =>
                entry.id
                !== id,
        )

    if (
        modalEntries.length
        === 0
    ) {
        unlockApplicationBackground()
    }

    publishModalStackChange()
}

export function isRegisteredModal(
    id:
        symbol,
): boolean {
    return modalEntries.some(
        (
            entry,
        ) =>
            entry.id
            === id,
    )
}

export function isTopmostModal(
    id:
        symbol,
): boolean {
    return modalEntries[
        modalEntries.length
        - 1
    ]?.id
        === id
}

function getTopmostModal():
    ModalEntry | null {
    return modalEntries[
        modalEntries.length
        - 1
    ] ?? null
}

export function subscribeModalStack(
    listener:
        () => void,
): () => void {
    modalStackListeners.add(
        listener,
    )

    return () => {
        modalStackListeners.delete(
            listener,
        )
    }
}

export function getModalStackSnapshot():
    number {
    return modalStackVersion
}

function publishModalStackChange() {
    modalStackVersion +=
        1

    modalStackListeners.forEach(
        (
            listener,
        ) =>
            listener(),
    )
}

function lockApplicationBackground() {
    const applicationRoot =
        document.getElementById(
            'root',
        )

    if (
        applicationRoot
    ) {
        rootPreviouslyInert =
            applicationRoot.hasAttribute(
                'inert',
            )

        rootPreviousAriaHidden =
            applicationRoot.getAttribute(
                'aria-hidden',
            )

        applicationRoot.setAttribute(
            'inert',
            '',
        )

        applicationRoot.setAttribute(
            'aria-hidden',
            'true',
        )
    }

    bodyPreviouslyLocked =
        document.body.classList.contains(
            'modal-open',
        )

    document.body.classList.add(
        'modal-open',
    )
}

function unlockApplicationBackground() {
    const applicationRoot =
        document.getElementById(
            'root',
        )

    if (
        applicationRoot
    ) {
        if (
            !rootPreviouslyInert
        ) {
            applicationRoot.removeAttribute(
                'inert',
            )
        }

        if (
            rootPreviousAriaHidden
                === null
        ) {
            applicationRoot.removeAttribute(
                'aria-hidden',
            )
        } else {
            applicationRoot.setAttribute(
                'aria-hidden',
                rootPreviousAriaHidden,
            )
        }
    }

    if (
        !bodyPreviouslyLocked
    ) {
        document.body.classList.remove(
            'modal-open',
        )
    }
}

export function restoreFocusAfterModalClose(
    previous:
        HTMLElement | null,
) {
    window.requestAnimationFrame(
        () => {
            const topmost =
                getTopmostModal()

            if (
                topmost
            ) {
                if (
                    previous?.isConnected
                    && topmost.card.contains(
                        previous,
                    )
                ) {
                    previous.focus({
                        preventScroll: true,
                    })

                    return
                }

                resolveInitialFocus(
                    topmost.card,
                    undefined,
                ).focus({
                    preventScroll: true,
                })

                return
            }

            if (
                previous?.isConnected
            ) {
                previous.focus({
                    preventScroll: true,
                })
            }
        },
    )
}

export function getOrCreateModalRoot():
    HTMLElement {
    const existing =
        document.getElementById(
            'modal-root',
        )

    if (
        existing
    ) {
        return existing
    }

    const created =
        document.createElement(
            'div',
        )

    created.id =
        'modal-root'

    document.body.append(
        created,
    )

    return created
}

// frontend/src/shared/ui/modal/useModalLifecycle.ts
// Ответственность модуля: регистрация окна, обработка клавиатуры и закрытия.

import { useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react'
import type { PointerEvent as ReactPointerEvent, RefObject } from 'react'
import { resolveInitialFocus, getFocusableElements } from './modalFocus'
import {
    getOrCreateModalRoot,
    getModalStackSnapshot,
    isRegisteredModal,
    isTopmostModal,
    registerModal,
    restoreFocusAfterModalClose,
    subscribeModalStack,
    unregisterModal,
} from './modalStack'

type LifecycleOptions = {
    onClose: () => void
    closeDisabled: boolean
    closeOnBackdrop: boolean
    closeOnEscape: boolean
    initialFocusRef?: RefObject<HTMLElement | null>
}

export function useModalLifecycle({
    onClose,
    closeDisabled,
    closeOnBackdrop,
    closeOnEscape,
    initialFocusRef,
}: LifecycleOptions) {
    const cardRef =
        useRef<HTMLDivElement | null>(
            null,
        )

    const backdropRef =
        useRef<HTMLDivElement | null>(
            null,
        )

    const previousActiveElementRef =
        useRef<HTMLElement | null>(
            null,
        )

    const [modalId] =
        useState(
            () =>
                Symbol(
                    'safeai-modal',
                ),
        )

    const onCloseRef =
        useRef(onClose)

    const closeDisabledRef =
        useRef(closeDisabled)

    const closeOnBackdropRef =
        useRef(closeOnBackdrop)

    const closeOnEscapeRef =
        useRef(closeOnEscape)

    const initialFocusRefRef =
        useRef(initialFocusRef)

    useLayoutEffect(() => {
        onCloseRef.current =
            onClose

        closeDisabledRef.current =
            closeDisabled

        closeOnBackdropRef.current =
            closeOnBackdrop

        closeOnEscapeRef.current =
            closeOnEscape

        initialFocusRefRef.current =
            initialFocusRef
    }, [
        onClose,
        closeDisabled,
        closeOnBackdrop,
        closeOnEscape,
        initialFocusRef,
    ])

    const [portalRoot] =
        useState<HTMLElement>(
            getOrCreateModalRoot,
        )

    useSyncExternalStore(
        subscribeModalStack,
        getModalStackSnapshot,
        getModalStackSnapshot,
    )

    const registered =
        isRegisteredModal(
            modalId,
        )

    const topmost =
        !registered
        || isTopmostModal(
            modalId,
        )

    useLayoutEffect(() => {
        const backdrop =
            backdropRef.current

        if (!backdrop) {
            return
        }

        if (topmost) {
            backdrop.removeAttribute(
                'inert',
            )

            backdrop.removeAttribute(
                'aria-hidden',
            )
        } else {
            backdrop.setAttribute(
                'inert',
                '',
            )

            backdrop.setAttribute(
                'aria-hidden',
                'true',
            )
        }
    }, [
        topmost,
    ])

    useLayoutEffect(() => {
        const card =
            cardRef.current

        if (!card) {
            return
        }

        previousActiveElementRef.current =
            document.activeElement
                instanceof HTMLElement
                ? document.activeElement
                : null

        resolveInitialFocus(
            card,
            initialFocusRefRef.current,
        ).focus({
            preventScroll: true,
        })

        registerModal(
            modalId,
            card,
        )

        function handleKeyDown(
            event: KeyboardEvent,
        ) {
            if (
                !isTopmostModal(
                    modalId,
                )
            ) {
                return
            }

            const currentCard =
                cardRef.current

            if (!currentCard) {
                return
            }

            if (
                event.key
                    === 'Escape'
            ) {
                event.preventDefault()
                event.stopPropagation()

                if (
                    closeOnEscapeRef.current
                    && !closeDisabledRef.current
                ) {
                    onCloseRef.current()
                }

                return
            }

            if (
                event.key
                    !== 'Tab'
            ) {
                return
            }

            const focusable =
                getFocusableElements(
                    currentCard,
                )

            if (
                focusable.length
                    === 0
            ) {
                event.preventDefault()

                currentCard.focus({
                    preventScroll: true,
                })

                return
            }

            const first =
                focusable[0]

            const last =
                focusable[
                    focusable.length - 1
                ]

            if (
                !first
                || !last
            ) {
                return
            }

            const active =
                document.activeElement

            if (
                !currentCard.contains(
                    active,
                )
            ) {
                event.preventDefault()

                first.focus({
                    preventScroll: true,
                })

                return
            }

            if (
                event.shiftKey
                && active === first
            ) {
                event.preventDefault()

                last.focus({
                    preventScroll: true,
                })

                return
            }

            if (
                !event.shiftKey
                && active === last
            ) {
                event.preventDefault()

                first.focus({
                    preventScroll: true,
                })
            }
        }

        document.addEventListener(
            'keydown',
            handleKeyDown,
            true,
        )

        return () => {
            document.removeEventListener(
                'keydown',
                handleKeyDown,
                true,
            )

            unregisterModal(
                modalId,
            )

            restoreFocusAfterModalClose(
                previousActiveElementRef.current,
            )
        }
    }, [
        modalId,
    ])

    function handleBackdropPointerDown(
        event:
            ReactPointerEvent<HTMLDivElement>,
    ) {
        if (
            event.target
                !== event.currentTarget
            || !isTopmostModal(
                modalId,
            )
            || !closeOnBackdropRef.current
            || closeDisabledRef.current
        ) {
            return
        }

        onCloseRef.current()
    }


    return {
        backdropRef,
        cardRef,
        portalRoot,
        topmost,
        onCloseRef,
        closeDisabledRef,
        handleBackdropPointerDown,
    }
}

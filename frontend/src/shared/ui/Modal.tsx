// frontend/src/shared/ui/Modal.tsx
// Ответственность модуля: публичный компонент и разметка модального окна.

import { useId } from 'react'
import type { ReactNode, RefObject } from 'react'
import { createPortal } from 'react-dom'
import { useModalLifecycle } from './modal/useModalLifecycle'
import { useModalResize } from './modal/useModalResize'
import type { ModalResizeOptions, ResizeDirection } from './modal/modalGeometry'

export type { ModalResizeOptions } from './modal/modalGeometry'

type ModalProps = {
    title: string
    children: ReactNode
    footer?: ReactNode
    onClose: () => void

    closeDisabled?: boolean
    closeOnBackdrop?: boolean
    closeOnEscape?: boolean

    size?: 'sm' | 'md' | 'lg'
    className?: string
    resize?: ModalResizeOptions
    // Разрешать перемещение мышью за заголовок (по умолчанию — да).
    dragEnabled?: boolean

    descriptionId?: string
    initialFocusRef?:
        RefObject<HTMLElement | null>
}

const RESIZE_DIRECTIONS: readonly ResizeDirection[] = [
    'n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw',
]

function Modal({
    title,
    children,
    footer,
    onClose,

    closeDisabled = false,
    closeOnBackdrop = true,
    closeOnEscape = true,

    size = 'md',
    className = '',
    resize,
    dragEnabled = true,
    descriptionId,
    initialFocusRef,
}: ModalProps) {
    const titleId = useId()
    const {
        backdropRef,
        cardRef,
        portalRoot,
        topmost,
        onCloseRef,
        closeDisabledRef,
        handleBackdropPointerDown,
    } = useModalLifecycle({
        onClose,
        closeDisabled,
        closeOnBackdrop,
        closeOnEscape,
        initialFocusRef,
    })
    const {
        resizeState,
        resizeBounds,
        maximized,
        cardStyle,
        toggleMaximized,
        handleResizePointerDown,
        handleResizePointerMove,
        handleResizePointerEnd,
        handleDragPointerDown,
        handleDragPointerMove,
        handleDragPointerEnd,
    } = useModalResize(resize, topmost)

    return createPortal(
        <div
            ref={backdropRef}
            className="modal-backdrop"
            onPointerDown={
                handleBackdropPointerDown
            }
        >
            <div
                ref={cardRef}
                className={[
                    'modal-card',
                    `modal-card--${size}`,
                    resizeState.enabled
                        ? 'modal-card--resizable'
                        : '',
                    maximized
                        ? 'modal-card--maximized'
                        : '',
                    className,
                ]
                    .filter(Boolean)
                    .join(' ')}
                role="dialog"
                aria-modal="true"
                aria-labelledby={
                    titleId
                }
                aria-describedby={
                    descriptionId
                }
                aria-busy={
                    closeDisabled
                }
                tabIndex={-1}
                style={
                    cardStyle
                }
            >
                <div
                    className={[
                        'modal-header',
                        resizeState.enabled && dragEnabled
                            ? 'modal-drag-handle'
                            : '',
                    ]
                        .filter(Boolean)
                        .join(' ')}
                    data-modal-drag-handle={
                        resizeState.enabled && dragEnabled
                            ? 'true'
                            : undefined
                    }
                    data-modal-maximized={
                        maximized
                            ? 'true'
                            : 'false'
                    }
                    onPointerDown={
                        dragEnabled ? handleDragPointerDown : undefined
                    }
                    onPointerMove={
                        dragEnabled ? handleDragPointerMove : undefined
                    }
                    onPointerUp={
                        dragEnabled ? handleDragPointerEnd : undefined
                    }
                    onPointerCancel={
                        dragEnabled ? handleDragPointerEnd : undefined
                    }
                    onLostPointerCapture={
                        dragEnabled ? handleDragPointerEnd : undefined
                    }
                    onDoubleClick={(event) => {
                        if (!dragEnabled) {
                            return
                        }

                        const eventTarget =
                            event.target

                        if (
                            eventTarget instanceof Element
                            && eventTarget.closest(
                                'button, a, input, select, textarea, [role="button"]',
                            )
                        ) {
                            return
                        }

                        toggleMaximized()
                    }}
                >
                    <h2
                        id={titleId}
                    >
                        {title}
                    </h2>

                    <div className="modal-window-actions">
                        {resizeState.enabled
                            && resizeState.options.maximizable
                            && (
                                <button
                                    type="button"
                                    className="modal-maximize-button"
                                    data-modal-maximize="true"
                                    aria-label={
                                        maximized
                                            ? 'Восстановить размер окна'
                                            : 'Развернуть окно на весь экран'
                                    }
                                    aria-pressed={maximized}
                                    title={
                                        maximized
                                            ? 'Восстановить размер'
                                            : 'Развернуть на весь экран'
                                    }
                                    onClick={toggleMaximized}
                                >
                                    <span aria-hidden="true" />
                                </button>
                            )}

                        <button
                            type="button"
                            className={
                                'modal-close-button'
                            }
                            data-modal-close="true"
                            aria-label={
                                closeDisabled
                                    ? (
                                        'Закрытие недоступно '
                                        + 'во время выполнения операции'
                                    )
                                    : 'Закрыть окно'
                            }
                            disabled={
                                closeDisabled
                            }
                            onClick={() => {
                                if (
                                    !closeDisabledRef
                                        .current
                                ) {
                                    onCloseRef.current()
                                }
                            }}
                        >
                            ×
                        </button>
                    </div>
                </div>

                <div className="modal-body">
                    {children}
                </div>

                {footer !== undefined
                    && footer !== null
                    && (
                        <div className="modal-footer">
                            {footer}
                        </div>
                    )}

                {resizeState.enabled
                    && resizeBounds
                    && !maximized
                    && (
                        <div
                            className={
                                'modal-resize-layer'
                            }
                            aria-hidden="true"
                        >
                            {RESIZE_DIRECTIONS.map(
                                (
                                    direction,
                                ) => (
                                    <div
                                        key={
                                            direction
                                        }
                                        className={[
                                            'modal-resize-handle',
                                            `modal-resize-handle--${direction}`,
                                        ].join(' ')}
                                        data-modal-resize-handle={
                                            direction
                                        }
                                        onPointerDown={(
                                            event,
                                        ) => {
                                            handleResizePointerDown(
                                                direction,
                                                event,
                                            )
                                        }}
                                        onPointerMove={
                                            handleResizePointerMove
                                        }
                                        onPointerUp={
                                            handleResizePointerEnd
                                        }
                                        onPointerCancel={
                                            handleResizePointerEnd
                                        }
                                    />
                                ),
                            )}
                        </div>
                    )}
            </div>
        </div>,
        portalRoot,
    )
}

export default Modal

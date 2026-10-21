// frontend/src/shared/ui/ResizableScrollRegion.tsx
// ============================================================
// frontend/src/shared/ui/ResizableScrollRegion.tsx
// ============================================================
import type { CSSProperties } from 'react'
import type { ResizableScrollRegionProps } from './resizableScrollRegion/regionTypes'
import { useResizableScrollRegion } from './resizableScrollRegion/useResizableScrollRegion'
import './ResizableScrollRegion.css'

export type { ResizableScrollRegionProps } from './resizableScrollRegion/regionTypes'

function ResizableScrollRegion(props: ResizableScrollRegionProps) {
    const {
        upper, children, footer, label, className = '', upperClassName = '',
        lowerClassName = '', viewportClassName = '', footerClassName = '',
        defaultHeight = 420, autoFitHeight, preserveUpperContent = false,
    } = props
    const {
        rootRef, upperRef, viewportRef, manualHeight, renderedHeight,
        effectiveProtectedUpperHeight, effectiveMinHeight, effectiveMaxHeight,
        beginResize, moveResize, finishResize, restoreBodyState,
        resetHeight, handleKeyDown,
    } = useResizableScrollRegion(props)
    return (
        <div
            ref={rootRef}
            className={[
                'resizable-scroll-region',
                preserveUpperContent
                    ? 'resizable-scroll-region--preserve-upper'
                    : '',
                autoFitHeight != null && !manualHeight && autoFitHeight < defaultHeight
                    ? 'resizable-scroll-region--auto-fit'
                    : '',
                className,
            ]
                .filter(Boolean)
                .join(' ')}
            style={{
                '--resizable-scroll-height':
                    `${renderedHeight}px`,

                '--resizable-upper-min-height':
                    `${effectiveProtectedUpperHeight}px`,
            } as CSSProperties}
        >
            <div
                ref={upperRef}
                className={[
                    'resizable-scroll-region__upper',
                    upperClassName,
                ]
                    .filter(Boolean)
                    .join(' ')}
            >
                {upper}
            </div>

            <div
                className={[
                    'resizable-scroll-region__lower',
                    lowerClassName,
                ]
                    .filter(Boolean)
                    .join(' ')}
            >
                <div
                    className={
                        'resizable-scroll-region__handle'
                    }
                    role="separator"
                    aria-label={
                        `Изменить размер области: ${label}`
                    }
                    aria-orientation="horizontal"
                    aria-valuemin={
                        Math.round(
                            effectiveMinHeight,
                        )
                    }
                    aria-valuemax={
                        Math.round(
                            effectiveMaxHeight,
                        )
                    }
                    aria-valuenow={
                        Math.round(
                            renderedHeight,
                        )
                    }
                    tabIndex={0}
                    title="Потяните вверх или вниз, чтобы изменить высоту. Двойной щелчок — вернуть стандартную высоту."
                    onDoubleClick={resetHeight}
                    onPointerDown={
                        beginResize
                    }
                    onPointerMove={
                        moveResize
                    }
                    onPointerUp={
                        finishResize
                    }
                    onPointerCancel={
                        finishResize
                    }
                    onLostPointerCapture={
                        restoreBodyState
                    }
                    onKeyDown={
                        handleKeyDown
                    }
                >
                    <span
                        aria-hidden="true"
                    />
                </div>

                <div
                    className={[
                        'resizable-scroll-region__viewport',
                        viewportClassName,
                    ]
                        .filter(Boolean)
                        .join(' ')}
                    ref={viewportRef}
                >
                    {children}
                </div>

                {footer !== undefined
                    && footer !== null
                    && (
                        <div
                            className={[
                                'resizable-scroll-region__footer',
                                footerClassName,
                            ]
                                .filter(Boolean)
                                .join(' ')}
                        >
                            {footer}
                        </div>
                    )}
            </div>
        </div>
    )
}

export default ResizableScrollRegion

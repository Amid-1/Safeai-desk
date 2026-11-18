// Fixed, viewport-clamped window geometry; dragging and maximizing reuse the shared Modal engine.
// Edge-resize handles are deliberately disabled for read-only profile cards.
import type { ModalResizeOptions } from './Modal'

export const DETAIL_ORGANIZATION_WINDOW: ModalResizeOptions = {
    initialWidth: 720,
    initialHeight: 725,
    minWidth: 320,
    minHeight: 360,
    maxWidth: 1000,
    maxHeight: 920,
    maximizable: true,
    resizable: false,
}

export const DETAIL_USER_WINDOW: ModalResizeOptions = {
    initialWidth: 700,
    initialHeight: 690,
    minWidth: 320,
    minHeight: 360,
    maxWidth: 980,
    maxHeight: 900,
    maximizable: true,
    resizable: false,
}

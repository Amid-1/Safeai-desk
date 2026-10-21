// frontend/src/features/usage/lib/usagePanelHeight.ts
import type { UsageReportTab } from './adminUsage.helpers'

/**
 * Initial height of the complete lower pane (including divider and footer).
 * The actual viewport clamp belongs to ResizableScrollRegion. In particular,
 * this is only a preference: it must never override a manual user resize.
 */
export function autoFitUsageHeight(
    naturalHeight: number | null,
    tab: UsageReportTab,
): number {
    const floor = tab === 'daily' ? 490 : 370
    const fallback = tab === 'daily' ? 520 : 450
    const desired = naturalHeight !== null
        && Number.isFinite(naturalHeight)
        && naturalHeight > 0
        ? naturalHeight
        : fallback
    return Math.min(590, Math.max(floor, Math.ceil(desired)))
}

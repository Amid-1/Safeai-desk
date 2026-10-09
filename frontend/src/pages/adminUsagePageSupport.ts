// frontend/src/pages/adminUsagePageSupport.ts

/**
 * Compatibility facade for older focused usage components.
 *
 * New code should import from adminUsage.helpers (or the focused modules).
 * This file contains no business logic and can be removed in a later cleanup
 * once every legacy import has migrated.
 */
export {
    countPart,
    defaultUtcRange,
    formatCount,
    formatIsoDate,
    isAbortError,
    parsePage,
    parseTab,
    reportDescription,
    reportTitle,
    trimDecimal,
    validateFilters,
} from './adminUsage.helpers'

export type {
    UsageReportTab,
} from './adminUsage.helpers'
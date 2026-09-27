// frontend/src/pages/adminUsage.helpers.ts

/**
 * Stable public barrel for usage-page helpers.
 *
 * Filter/URL rules and presentation formatting are physically separated, but
 * this module preserves existing imports while the codebase migrates.
 */
export {
    buildUsageSearchParams,
    createDefaultUsageDraftFilter,
    defaultUtcRange,
    isAbortError,
    isUsageOrganizationId,
    MAX_USAGE_RANGE_DAYS,
    parsePage,
    parseTab,
    readUsageUrlState,
    toUsageFilter,
    validateFilters,
} from './adminUsageFilterState'

export type {
    NormalizedUsageFilter,
    UsageDraftFilter,
    UsageReportTab,
    UsageUrlState,
    UsageUrlValues,
} from './adminUsageFilterState'

export {
    countPart,
    formatCount,
    formatIsoDate,
    reportDescription,
    reportTitle,
    trimDecimal,
} from './adminUsagePresentation'

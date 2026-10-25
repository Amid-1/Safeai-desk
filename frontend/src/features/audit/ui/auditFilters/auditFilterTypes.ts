// frontend/src/features/audit/ui/auditFilters/auditFilterTypes.ts
// ============================================================
// frontend/src/features/audit/ui/auditFilters/auditFilterTypes.ts
// ============================================================
import type { Dispatch, SetStateAction } from 'react'
import type { AuditActorDirectoryItem, AuditTargetOrganizationDirectoryItem } from '../../api/adminAuditApi'
import type { AuditDraftFilter, DatePreset } from '../types'

export type AuditFiltersProps = {
    draftFilter: AuditDraftFilter

    eventTypes: string[]

    organizations:
        AuditTargetOrganizationDirectoryItem[]

    actors:
        AuditActorDirectoryItem[]

    superAdmin: boolean
    loading: boolean
    directoriesLoading: boolean

    eventTypesError: string
    organizationsError: string
    actorsError: string
    filterError: string
    filtersDirty: boolean

    onFilterChange:
        Dispatch<
            SetStateAction<
                AuditDraftFilter
            >
        >

    onActorSearch: (
        query: string,
        targetOrganizationId?: string,
    ) => Promise<void>

    onOrganizationSearch:
        (query: string) => Promise<void>

    onDatePreset:
        (preset: DatePreset) => void

    onApply: () => void
    onReset: () => void
}

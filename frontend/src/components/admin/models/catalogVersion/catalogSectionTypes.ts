// ============================================================
// frontend/src/components/admin/models/catalogVersion/catalogSectionTypes.ts
// ============================================================
import type { Dispatch, SetStateAction } from 'react'
import type { CatalogDraft } from '../modelControlPlaneSupport'

export type CatalogSectionProps = {
    draft: CatalogDraft
    setDraft: Dispatch<SetStateAction<CatalogDraft>>
}

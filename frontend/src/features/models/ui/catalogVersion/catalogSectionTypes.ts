// frontend/src/features/models/ui/catalogVersion/catalogSectionTypes.ts
// ============================================================
// frontend/src/features/models/ui/catalogVersion/catalogSectionTypes.ts
// ============================================================
import type { Dispatch, SetStateAction } from 'react'
import type { CatalogDraft } from '../modelControlPlaneSupport'

export type CatalogSectionProps = {
    draft: CatalogDraft
    setDraft: Dispatch<SetStateAction<CatalogDraft>>
}

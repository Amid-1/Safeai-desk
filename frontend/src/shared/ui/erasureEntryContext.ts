// frontend/src/shared/ui/erasureEntryContext.ts
import { createContext } from 'react'

export const ErasureEntryContext = createContext(false)

export const ErasureCountsContext = createContext<{ registryCount: number | null; reviewCount: number | null } | null>(null)

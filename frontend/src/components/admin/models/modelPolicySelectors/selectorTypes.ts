// frontend/src/components/admin/models/modelPolicySelectors/selectorTypes.ts
// Назначение: строгие типы селекторов и состояние взаимодействия.

import type {RefObject} from 'react'
import type {ModelCatalogEntry, RuntimeModelStatus} from '../../../../api/modelApi'

export type SelectorKind =
    | 'allow'
    | 'deny'

export type ModelKeySelectorInteractionState = {
    hasPendingInput: boolean
    hasError: boolean
}

export type RoutingContextProps = {
    catalog: ModelCatalogEntry[]
    effectiveCatalog: ModelCatalogEntry[]
    runtime: RuntimeModelStatus
}

export type ModelKeySelectorProps = RoutingContextProps & {
    label: string
    hint: string
    kind: SelectorKind
    value: string
    conflictingValue: string
    disabled?: boolean
    inputRef?: RefObject<HTMLInputElement | null>
    onChange: (value: string) => void
    onInteractionStateChange?: (
        state: ModelKeySelectorInteractionState,
    ) => void
}

export type DefaultModelSelectorProps = RoutingContextProps & {
    allowModelKeys: string
    denyModelKeys: string
    value: string
    disabled?: boolean
    onChange: (value: string) => void
}

export type NormalizedModelKey =
    | {
        valid: true
        value: string
    }
    | {
        valid: false
        message: string
    }

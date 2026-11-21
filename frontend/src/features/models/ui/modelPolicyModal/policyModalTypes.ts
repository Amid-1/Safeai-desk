// frontend/src/features/models/ui/modelPolicyModal/policyModalTypes.ts
// Контракт публичного модального окна правил моделей.

import type {
    CreateOrganizationModelPolicyVersionRequest,
    ModelCatalogEntry,
    ModelPolicyPreview,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../../entities/model/api/modelApi'
import type {PolicyEditorTarget} from './policyFormTabsConfig'

export type ModelPolicyModalProps = {
    initialTarget?: PolicyEditorTarget
    policy:
        OrganizationModelPolicy

    catalog:
        ModelCatalogEntry[]

    effectiveCatalog:
        ModelCatalogEntry[]

    runtime:
        RuntimeModelStatus

    organizationId:
        string

    organizationName:
        string | null

    pending:
        boolean

    onClose:
        () => void

    onPreview: (
        request:
            CreateOrganizationModelPolicyVersionRequest,
    ) => Promise<ModelPolicyPreview>

    onSubmit: (
        request:
            CreateOrganizationModelPolicyVersionRequest,
    ) => Promise<void>
}

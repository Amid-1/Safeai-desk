// frontend/src/components/admin/models/modelPolicyModal/policyModalTypes.ts
// Контракт публичного модального окна правил моделей.

import type {
    CreateOrganizationModelPolicyVersionRequest,
    ModelCatalogEntry,
    ModelPolicyPreview,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../../api/modelApi'

export type ModelPolicyModalProps = {
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

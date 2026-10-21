// frontend/src/api/modelApi.ts
// Назначение: стабильная публичная точка входа API моделей.

// Публичные имена и сигнатуры сохранены для всех существующих потребителей.
export type {
    ModelCapability,
    ModelModality,
    ModelLifecycle,
    ModelRetentionStatus,
    ModelTrainingUseStatus,
    ModelPricingStatus,
    ModelCatalogSource,
    BudgetEnforcement,
    ModelRouteOutcome,
    MonthlyCostState,
    ModelRouteReason,
    ModelCatalogEntry,
    RuntimeRoutingMode,
    RuntimeDataRetentionStatus,
    RuntimeHealthStatus,
    RuntimePricingStatus,
    InputAccountingVersion,
    RuntimeModelStatus,
    RuntimeModelProbeStatus,
    RuntimeModelProbe,
    OrganizationModelPolicy,
    ModelRouteDecision,
    CreateModelCatalogVersionRequest,
    CreateOrganizationModelPolicyVersionRequest,
    ModelPolicyPreviewItem,
    ModelPolicyPreview,
} from './model/modelTypes'

export {
    RUNTIME_MODEL_PROBE_STATUSES,
    RUNTIME_ROUTING_MODES,
    RUNTIME_DATA_RETENTION_STATUSES,
    RUNTIME_HEALTH_STATUSES,
    RUNTIME_PRICING_STATUSES,
    INPUT_ACCOUNTING_VERSIONS,
    MODEL_CAPABILITIES,
    MODEL_MODALITIES,
    MODEL_LIFECYCLES,
    MODEL_RETENTION_STATUSES,
    MODEL_TRAINING_USE_STATUSES,
    MODEL_PRICING_STATUSES,
    MODEL_CATALOG_SOURCES,
    BUDGET_ENFORCEMENTS,
    MONTHLY_COST_STATES,
    MODEL_ROUTE_OUTCOMES,
    MODEL_ROUTE_REASONS,
} from './model/modelEnums'

export {
    getRuntimeModelStatus,
    probeRuntimeModel,
    getModelCatalog,
    getEffectiveModelCatalog,
    createModelCatalogVersion,
    importRuntimeModelCatalog,
    getOrganizationModelPolicy,
    createOrganizationModelPolicyVersion,
    previewOrganizationModelPolicy,
    getModelRouteDecision,
} from './model/modelClient'

export {
    parseRuntimeModelStatus,
    parseRuntimeModelProbe,
    parseModelCatalogEntry,
    parseOrganizationModelPolicy,
    parseModelRouteDecision,
    parseModelPolicyPreview,
} from './model/modelParsers'

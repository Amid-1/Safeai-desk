// frontend/src/features/models/ui/modelConfigurationReadiness.ts
import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
    RuntimeModelProbe,
} from '../../../entities/model/api/modelApi'

/** Server evidence is necessary; a missing/expired attestation is not a guarantee. */
export function runtimeDataGuarantees(runtime: RuntimeModelStatus, now = Date.now()) {
    const evidence = runtime.dataAssurance
    const localMock = runtime.provider === 'mock' && evidence?.source === 'LOCAL_MOCK'
    const verified = Date.parse(evidence?.verifiedAt ?? '')
    const expires = Date.parse(evidence?.expiresAt ?? '')
    const contractual = !!evidence?.source && evidence.source !== 'LOCAL_MOCK'
        && Number.isFinite(verified) && Number.isFinite(expires) && verified <= now && now < expires
    const valid = localMock || contractual
    return {
        noTraining: valid && evidence?.noTraining === true,
        zeroDataRetention: valid && evidence?.zeroDataRetention === true,
    }
}

/**
 * Advisory configuration summary only. Never claims that a real route is
 * allowed: server-owned preview / decision handles the definitive verdict.
 * In particular, data policies, capabilities, cost and budget can deny a
 * request even if Runtime and an effective catalog entry match.
 */
export function modelConfigurationReadiness(
    runtime: RuntimeModelStatus,
    effectiveCatalog: readonly ModelCatalogEntry[],
    policy: OrganizationModelPolicy,
    probe: RuntimeModelProbe | null = null,
) {
    const compatible = effectiveCatalog.filter((entry) =>
        (entry.lifecycle === 'ACTIVE' || entry.lifecycle === 'DEPRECATED')
        && entry.provider === runtime.provider
        && entry.providerModelId === runtime.model,
    )

    const allowed = compatible.filter((entry) => {
        const key = entry.modelKey.toLowerCase()
        return !policy.enabled || (
            !policy.denyModelKeys.some((item) => item.toLowerCase() === key)
            && (
                policy.allowModelKeys.length === 0
                || policy.allowModelKeys.some((item) => item.toLowerCase() === key)
            )
        )
    })

    const blockers: string[] = []
    if (!policy.configured) {
        blockers.push('Правила организации ещё не настроены. AI-запросы блокируются до настройки доступа.')
    }
    if (!runtime.enabled) {
        blockers.push('Подключённая модель отключена в настройках сервера.')
    }
    if (compatible.length === 0) {
        blockers.push('В каталоге нет действующей версии, совпадающей с подключённой моделью.')
    } else if (policy.enabled && allowed.length === 0) {
        blockers.push('Правила доступа исключают совпадающие версии подключённой модели.')
    }
    const matchingProbe = runtime.enabled && probe?.provider === runtime.provider && probe.model === runtime.model ? probe : null
    if (matchingProbe ? matchingProbe.status !== 'AVAILABLE' : runtime.healthStatus === 'UNAVAILABLE') {
        blockers.push('Последняя проверка подключения завершилась неуспешно.')
    }
    const guarantees = runtimeDataGuarantees(runtime)
    if (policy.enabled && policy.requireNoTraining && !guarantees.noTraining) {
        blockers.push('Запрет использования данных для обучения не подтверждён для текущего подключения.')
    }
    if (policy.enabled && policy.requireZeroDataRetention && !guarantees.zeroDataRetention) {
        blockers.push('Нулевое хранение данных не подтверждено для текущего подключения.')
    }

    if (policy.enabled && policy.requireNoTraining && allowed.length > 0
        && allowed.every(entry => entry.trainingUseStatus !== 'NOT_USED' && entry.trainingUseStatus !== 'CONTRACTUAL_NO_TRAINING')) {
        blockers.push('В действующих записях каталога не подтверждён запрет обучения.')
    }
    if (policy.enabled && policy.requireZeroDataRetention && allowed.length > 0
        && allowed.every(entry => entry.retentionStatus !== 'ZERO_DATA_RETENTION')) {
        blockers.push('В действующих записях каталога не подтверждено нулевое хранение.')
    }
    if (policy.enabled && policy.requireCompletePricing && allowed.length > 0
        && allowed.every(entry => !entry.pricingComplete)) {
        blockers.push('В действующих записях каталога отсутствуют полные сведения о стоимости.')
    }
    return {
        compatibleCount: compatible.length,
        allowedCount: allowed.length,
        policyEnabled: policy.enabled,
        runtimeEnabled: runtime.enabled,
        blockers,
    }
}

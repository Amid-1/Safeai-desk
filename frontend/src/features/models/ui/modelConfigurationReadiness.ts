// frontend/src/features/models/ui/modelConfigurationReadiness.ts
import type {
    ModelCatalogEntry,
    OrganizationModelPolicy,
    RuntimeModelStatus,
} from '../../../entities/model/api/modelApi'

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
    if (!runtime.enabled) {
        blockers.push('Подключённая модель отключена в настройках сервера.')
    }
    if (compatible.length === 0) {
        blockers.push('В каталоге нет действующей версии, совпадающей с подключённой моделью.')
    } else if (policy.enabled && allowed.length === 0) {
        blockers.push('Правила доступа исключают совпадающие версии подключённой модели.')
    }
    if (runtime.healthStatus === 'UNAVAILABLE') {
        blockers.push('Последняя проверка подключения завершилась неуспешно.')
    }

    return {
        compatibleCount: compatible.length,
        policyEnabled: policy.enabled,
        runtimeEnabled: runtime.enabled,
        blockers,
    }
}

// frontend/src/features/models/ui/modelPolicySelectors/selectorSupport.ts
// Назначение: общие правила нормализации и индикации каталога.

import type {ModelCatalogEntry, RuntimeModelStatus} from '../../../../entities/model/api/modelApi'
import {normalizeModelKey} from '../modelControlPlaneSupport'
import type {NormalizedModelKey, SelectorKind} from './selectorTypes'

export const MAX_POLICY_MODEL_KEYS = 200
export const MAX_VISIBLE_OPTIONS = 8

export function splitDraftModelKeys(
    value: string,
): string[] {
    if (!value.trim()) {
        return []
    }

    return value
        .split(/(?:\\[nr]|[\s,;])+/)
        .map((item) => item.trim())
        .filter(Boolean)
        .map((item) => item.toLowerCase())
}

export function uniqueModelKeys(
    values: string[],
): string[] {
    return Array.from(
        new Set(values),
    )
}

export function validateModelKey(
    value: string,
): NormalizedModelKey {
    try {
        return {
            valid: true,
            value: normalizeModelKey(value),
        }
    } catch (failure) {
        return {
            valid: false,
            message:
                failure instanceof Error
                    ? failure.message
                    : 'Некорректный ключ модели.',
        }
    }
}

export function catalogSearchText(
    entry: ModelCatalogEntry,
): string {
    return [
        entry.displayName,
        entry.modelKey,
        entry.provider,
        entry.providerModelId,
    ]
        .join(' ')
        .toLowerCase()
}

export function catalogByKey(
    catalog: ModelCatalogEntry[],
): Map<string, ModelCatalogEntry> {
    return new Map(
        catalog.map((entry) => [
            entry.modelKey.toLowerCase(),
            entry,
        ]),
    )
}

export function conflictMessage(
    kind: SelectorKind,
): string {
    return kind === 'allow'
        ? 'Эта модель уже находится в списке запрещённых.'
        : 'Эта модель уже находится в списке разрешённых.'
}

export function selectorDescription(
    kind: SelectorKind,
): string {
    return kind === 'allow'
        ? 'Если список заполнен, организация сможет использовать только выбранные модели.'
        : 'Выбранные модели будут запрещены для организации.'
}

export function modelExecutionLabel(
    modelKey: string,
    catalog: ModelCatalogEntry[],
    effectiveCatalog: ModelCatalogEntry[],
    runtime: RuntimeModelStatus,
): string {
    const normalized = modelKey.toLowerCase()

    const effective =
        effectiveCatalog.find(
            (entry) =>
                entry.modelKey.toLowerCase()
                    === normalized,
        ) ?? null

    if (effective) {
        const runtimeMatch =
            (
                effective.lifecycle === 'ACTIVE'
                || effective.lifecycle === 'DEPRECATED'
            )
            && effective.provider === runtime.provider
            && effective.providerModelId === runtime.model

        return runtimeMatch
            ? 'Используется сейчас'
            : 'Есть в каталоге, но сейчас подключена другая модель'
    }

    const latest =
        catalog.find(
            (entry) =>
                entry.modelKey.toLowerCase()
                    === normalized,
        ) ?? null

    if (!latest) {
        return 'нет в каталоге'
    }

    return 'Есть в последней версии каталога, но сервер не считает её действующей сейчас'
}

export function runtimeExecutionLabel(
    effectiveCatalog: ModelCatalogEntry[],
    runtime: RuntimeModelStatus,
): string {
    const match =
        effectiveCatalog.find(
            (entry) =>
                (
                    entry.lifecycle === 'ACTIVE'
                    || entry.lifecycle === 'DEPRECATED'
                )
                && entry.provider === runtime.provider
                && entry.providerModelId === runtime.model,
        )

    return match
        ? `${match.modelKey} · используется сейчас`
        : `${runtime.provider}/${runtime.model} · нет соответствующей действующей версии в каталоге`
}

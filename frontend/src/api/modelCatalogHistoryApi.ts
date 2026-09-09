// ============================================================
// frontend/src/api/modelCatalogHistoryApi.ts
// ============================================================
import {
    apiRequest,
} from './http'
import {
    parseModelCatalogEntry,
} from './modelApi'
import type {
    ModelCatalogEntry,
} from './modelApi'

type ModelCatalogHistoryRequestOptions = {
    signal?: AbortSignal
}

export async function getModelCatalogHistory(
    modelKey: string,
    options: ModelCatalogHistoryRequestOptions = {},
): Promise<ModelCatalogEntry[]> {
    const normalized = modelKey.trim().toLowerCase()

    if (!normalized) {
        throw new Error('modelKey не должен быть пустым')
    }

    const raw = await apiRequest<unknown>(
        `/api/admin/models/catalog/versions?modelKey=${encodeURIComponent(normalized)}`,
        {
            method: 'GET',
            signal: options.signal,
        },
    )

    if (!Array.isArray(raw)) {
        throw new Error(
            'Некорректный ответ API истории версий модели.',
        )
    }

    return raw.map((value, index) =>
        parseModelCatalogEntry(
            value,
            `modelCatalogHistory[${index}]`,
        ),
    )
}
